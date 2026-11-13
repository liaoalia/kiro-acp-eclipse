package io.github.liaoalia.kiroacp.core.session.context;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.liaoalia.kiroacp.core.acp.api.AgentInfo;
import io.github.liaoalia.kiroacp.core.host.Logger;
import io.github.liaoalia.kiroacp.core.host.Settings;
import io.github.liaoalia.kiroacp.core.host.Subscription;
import io.github.liaoalia.kiroacp.core.host.UiScheduler;
import io.github.liaoalia.kiroacp.core.host.Workspace;
import io.github.liaoalia.kiroacp.core.model.ContextChip;
import io.github.liaoalia.kiroacp.core.model.FileSuggestion;
import io.github.liaoalia.kiroacp.core.model.FromWebview;
import io.github.liaoalia.kiroacp.core.model.ImageAttachment;
import io.github.liaoalia.kiroacp.core.model.NoticeView.Severity;
import io.github.liaoalia.kiroacp.core.model.ToWebview;
import io.github.liaoalia.kiroacp.core.pure.context.ContentBlocks;
import io.github.liaoalia.kiroacp.core.pure.context.FileSearch;
import io.github.liaoalia.kiroacp.core.pure.context.Images;
import io.github.liaoalia.kiroacp.core.pure.context.Images.ImageRead;
import io.github.liaoalia.kiroacp.core.pure.context.Images.PromptImages;
import io.github.liaoalia.kiroacp.core.services.TextFiles;
import io.github.liaoalia.kiroacp.core.session.ChatController;
import io.github.liaoalia.kiroacp.core.session.ChatHooks;
import io.github.liaoalia.kiroacp.core.session.ChatHooks.PromptComposer;
import io.github.liaoalia.kiroacp.core.session.ChatHooks.PromptRequest;
import io.github.liaoalia.kiroacp.core.session.PageHandle;

/**
 * File, selection and image context of a prompt (the reference's
 * {@code features/context.ts} and the parts of its controller that deal with
 * chips and images; its decisions D10 and D29):
 *
 * <ul>
 * <li>the chips above the composer: the active editor's file when the user
 * wants it attached, selections sent from an editor, files dropped on the
 * composer;
 * <li>the {@code @} picker's search through the workspace;
 * <li>images: picked in a dialog or dropped as files, checked before a
 * message goes out, sent as ACP {@code image} blocks;
 * <li>the prompt itself: its content blocks and what the transcript shows of
 * them.
 * </ul>
 *
 * <p>Everything here runs on the controller thread, which in the IDE is the
 * UI thread, except what touches the disk: the search, reading images and
 * reading the text of a selection run on two worker threads and bring their
 * results back through the scheduler.
 */
public final class ChatContext implements AutoCloseable {

	/**
	 * What the feature needs of the IDE.
	 *
	 * @param editor         the active editor; {@code null} for none
	 * @param picker         the image file dialog; {@code null} for none
	 * @param excludedFolder folders the {@code @} search does not enter, besides the reference's
	 *                       ({@link FileSearch#EXCLUDED_FOLDERS}); called on a worker thread; {@code null} for none
	 */
	public record Host(Workspace workspace, Settings settings, UiScheduler scheduler, TextFiles textFiles, Logger logger,
			EditorContext editor, ImagePicker picker, Predicate<Path> excludedFolder) {

		public Host {
			Objects.requireNonNull(workspace, "workspace");
			Objects.requireNonNull(settings, "settings");
			Objects.requireNonNull(scheduler, "scheduler");
			Objects.requireNonNull(textFiles, "textFiles");
			logger = logger != null ? logger : Logger.NONE;
			editor = editor != null ? editor : EditorContext.NONE;
			picker = picker != null ? picker : ImagePicker.NONE;
		}
	}

	/** The images of the prompt checked last: a prompt is checked before it is sent, then composed, then echoed. */
	private record Checked(List<ImageAttachment> source, boolean accepted, PromptImages result) {
	}

	/** What the worker made of the image files of a pick or a drop. */
	private record ReadImages(List<ImageAttachment> images, List<String> reasons) {
	}

	/** What the worker made of a drop. */
	private record Dropped(List<Path> files, int refusedImages, ReadImages read) {
	}

	private final ChatController controller;
	private final Host host;
	private final ContextTracker tracker;
	private final ExecutorService scans = worker("kiro-context-search");
	private final ExecutorService reads = worker("kiro-context-files");
	private final List<Subscription> subscriptions = new ArrayList<>();
	private final AtomicInteger imageSeq = new AtomicInteger();
	/** The number of the latest search of each page; an older one still running gives up. Controller thread. */
	private final Map<PageHandle, AtomicLong> searches = new WeakHashMap<>();
	private Checked checked;
	private List<ContextChip> posted = List.of();
	private volatile boolean closed;

	private ChatContext(ChatController controller, Host host) {
		this.controller = controller;
		this.host = host;
		this.tracker = new ContextTracker(host.workspace()::label, host.settings()::attachActiveFile, this::chipsChanged);
	}

	/**
	 * Install the feature in a controller: its prompt composer, the check of
	 * a prompt's images and the handlers of {@code queryFiles},
	 * {@code removeChip}, {@code pickImages} and {@code dropFiles}.
	 *
	 * <p>The image check is a prompt interceptor, and the reference checks
	 * the images before it looks for a slash command: install this before
	 * whatever intercepts those.
	 */
	public static ChatContext install(ChatController controller, Host host) {
		ChatContext context = new ChatContext(controller, host);
		ChatHooks hooks = controller.hooks();
		hooks.setPromptComposer(context.new Composer());
		context.subscriptions.add(hooks.addPromptInterceptor(context::refuseBadImages));
		context.subscriptions.add(hooks.onMessage(FromWebview.QueryFiles.class, context::queryFiles));
		context.subscriptions.add(hooks.onMessage(FromWebview.RemoveChip.class, (message, page) -> context.removeChip(message.id())));
		context.subscriptions.add(hooks.onMessage(FromWebview.PickImages.class, (message, page) -> context.pickImages(page)));
		context.subscriptions.add(hooks.onMessage(FromWebview.DropFiles.class, (message, page) -> context.dropFiles(message.uris(), page)));
		// The setting decides whether the active file is a chip at all.
		context.subscriptions.add(host.settings().onChange(() -> context.onController(context::settingsChanged)));
		context.subscriptions.add(host.editor().onActiveFileChanged(file -> context.onController(() -> context.tracker.activeFileChanged(file))));
		context.onController(() -> context.tracker.activeFileChanged(host.editor().activeFile()));
		return context;
	}

	// ---------------------------------------------------------------------
	// Chips: controller thread
	// ---------------------------------------------------------------------

	/** The chips the composer shows. */
	public List<ContextChip> chips() {
		return tracker.chips();
	}

	/** Attach a file as an explicit chip. */
	public ContextChip addFileChip(Path file) {
		return tracker.addFileChip(file);
	}

	/**
	 * "Send Selection to Kiro Chat": a chip for the active editor's selection
	 * (its current line when nothing is selected), and the composers are told
	 * to take the focus.
	 *
	 * @return {@code false} when there is no editor of a file
	 */
	public boolean addSelectionChip() {
		if (tracker.addSelectionChip(host.editor().selection()) == null) {
			return false;
		}
		controller.broadcast(new ToWebview.FocusInput());
		return true;
	}

	public void removeChip(String id) {
		tracker.removeChip(id);
	}

	/** Drop the explicit chips, as after a prompt. */
	public void clearExplicit() {
		tracker.clearExplicit();
	}

	private void chipsChanged(List<ContextChip> chips) {
		posted = chips;
		controller.broadcast(new ToWebview.Chips(chips));
	}

	/** Any setting changed; the pages are only told when that changed the chips. */
	private void settingsChanged() {
		if (!tracker.chips().equals(posted)) {
			tracker.fire();
		}
	}

	// ---------------------------------------------------------------------
	// The prompt
	// ---------------------------------------------------------------------

	private boolean acceptsImages() {
		AgentInfo agent = controller.agentInfo();
		return agent != null && agent.acceptsImages();
	}

	private PromptImages check(List<ImageAttachment> images, boolean accepted) {
		Checked last = checked;
		if (last == null || last.source() != images || last.accepted() != accepted) {
			last = new Checked(images, accepted, Images.checkPromptImages(images, accepted));
			checked = last;
		}
		return last.result();
	}

	/**
	 * Images are checked before anything is echoed or the chips are cleared: a
	 * message with a refused image is not sent at all, not half-sent.
	 */
	private CompletionStage<Boolean> refuseBadImages(PromptRequest request) {
		// Without a session there is nothing to send to; that is said elsewhere.
		if (request.sessionId() == null || request.images().isEmpty()) {
			return CompletableFuture.completedFuture(false);
		}
		PromptImages images = check(request.images(), acceptsImages());
		if (images.refusal() == null) {
			return CompletableFuture.completedFuture(false);
		}
		controller.notice(request.sessionId(), Severity.WARNING, images.refusal());
		return CompletableFuture.completedFuture(true);
	}

	private final class Composer implements PromptComposer {

		@Override
		public List<ContextChip> chips() {
			return tracker.chips();
		}

		@Override
		public CompletionStage<List<JsonNode>> blocks(PromptRequest request, AgentInfo agent) {
			boolean embeddedContext = agent != null
					&& agent.agentCapabilities().path("promptCapabilities").path("embeddedContext").asBoolean(false);
			List<ImageAttachment> images = request.images().isEmpty() ? List.of()
					: check(request.images(), agent != null && agent.acceptsImages()).images();
			List<ContextChip> chips = request.chips();
			Supplier<List<JsonNode>> build = () -> ContentBlocks.buildPromptBlocks(request.text(), chips,
					path -> host.textFiles().read(Path.of(path)), embeddedContext, images);
			// Only the text of a selection has to be read.
			if (chips.stream().noneMatch(chip -> chip.kind() == ContextChip.Kind.SELECTION)) {
				return CompletableFuture.completedFuture(build.get());
			}
			return offThread(reads, build);
		}

		@Override
		public List<JsonNode> echo(PromptRequest request) {
			List<ImageAttachment> images = request.images().isEmpty() ? List.of()
					: check(request.images(), acceptsImages()).images();
			return ContentBlocks.echoBlocks(request.chips(), images);
		}

		@Override
		public void sent(PromptRequest request) {
			checked = null;
			tracker.clearExplicit();
		}
	}

	// ---------------------------------------------------------------------
	// The @ picker
	// ---------------------------------------------------------------------

	/**
	 * Search the workspace for a query of the {@code @} picker and answer the
	 * page with {@code fileSuggestions}. A page's newer query ends its older
	 * search, whose answer the page would ignore anyway.
	 */
	private void queryFiles(FromWebview.QueryFiles message, PageHandle page) {
		String query = message.query() != null ? message.query() : "";
		AtomicLong latest = searches.computeIfAbsent(page, key -> new AtomicLong());
		long ticket = latest.incrementAndGet();
		offThread(scans, () -> latest.get() != ticket ? null : searchFiles(query, FileSearch.MAX_SUGGESTIONS, () -> closed || latest.get() != ticket))
				.whenComplete((items, error) -> onController(() -> {
					if (error != null) {
						host.logger().warn("file search for \"" + query + "\" failed: " + error);
					}
					if (latest.get() == ticket && (items != null || error != null)) {
						page.post(new ToWebview.FileSuggestions(query, items != null ? items : List.of()));
					}
				}));
	}

	/**
	 * The reference's {@code searchFiles}: up to {@code max(max * 5, 50)}
	 * files whose path the query's glob matches, ranked, the best {@code max}
	 * of them. Slow.
	 */
	List<FileSuggestion> searchFiles(String query, int max, BooleanSupplier cancelled) {
		Workspace workspace = host.workspace();
		List<Path> files = FileScan.find(workspace.roots(), query, FileSearch.searchLimit(max), host.excludedFolder(), cancelled);
		List<FileSuggestion> items = new ArrayList<>(files.size());
		for (Path file : files) {
			items.add(new FileSuggestion(file.toString(), workspace.label(file).replace('\\', '/')));
		}
		List<FileSuggestion> sorted = FileSearch.sortByScore(query, items, FileSuggestion::label);
		return List.copyOf(sorted.subList(0, Math.min(max, sorted.size())));
	}

	// ---------------------------------------------------------------------
	// Images
	// ---------------------------------------------------------------------

	/**
	 * The composer's attach button: a file dialog for images. The ones that
	 * pass the checks go back to the page that asked as
	 * {@code imagesAttached} (it makes the previews), the others are named in
	 * one warning.
	 */
	private void pickImages(PageHandle page) {
		String sessionId = page.sessionId();
		if (!acceptsImages()) {
			controller.notice(sessionId, Severity.INFO, "Kiro does not accept images.");
			return;
		}
		CompletionStage<List<Path>> picked;
		try {
			picked = host.picker().pickImages(Images.IMAGE_EXTENSIONS);
		} catch (RuntimeException e) {
			picked = CompletableFuture.failedFuture(e);
		}
		picked.whenComplete((files, error) -> {
			if (error != null) {
				host.logger().error("the image dialog failed: " + error, error);
			}
			if (error != null || files == null || files.isEmpty()) {
				return;
			}
			List<DroppedUri> uris = files.stream().map(file -> new DroppedUri("file", "/" + file.getFileName(), file, file.toString())).toList();
			offThread(reads, () -> readImageFiles(uris)).whenComplete((read, failure) -> onController(() -> {
				if (failure != null) {
					host.logger().error("reading the picked images failed: " + failure, failure);
					return;
				}
				warn(sessionId, read.reasons());
				if (!read.images().isEmpty()) {
					page.post(new ToWebview.ImagesAttached(read.images()));
				}
			}));
		});
	}

	/**
	 * URIs dropped on the composer (a drag that carries a list of URIs, not
	 * the bytes): image files (by extension) are read like picked ones; any
	 * other {@code file:} URI naming a file becomes a file chip. Folders,
	 * other schemes and strings that are no URI are ignored.
	 *
	 * @param page the page they were dropped on
	 */
	public void dropFiles(List<String> uris, PageHandle page) {
		String sessionId = page.sessionId();
		boolean accepts = acceptsImages();
		List<String> dropped = uris != null ? List.copyOf(uris) : List.of();
		offThread(reads, () -> sortDrop(dropped, accepts)).whenComplete((drop, failure) -> onController(() -> {
			if (failure != null) {
				host.logger().error("handling dropped files failed: " + failure, failure);
				return;
			}
			drop.files().forEach(tracker::addFileChip);
			if (drop.refusedImages() > 0) {
				String what = drop.refusedImages() == 1 ? "the image was" : drop.refusedImages() + " images were";
				controller.notice(sessionId, Severity.INFO, "Kiro does not accept images, so " + what + " not attached.");
			}
			warn(sessionId, drop.read().reasons());
			List<ImageAttachment> images = drop.read().images();
			if (!images.isEmpty()) {
				page.post(new ToWebview.ImagesAttached(images));
			}
			if (!images.isEmpty() || !drop.files().isEmpty()) {
				page.post(new ToWebview.FocusInput());
			}
		}));
	}

	/** Worker thread. */
	private Dropped sortDrop(List<String> uris, boolean acceptsImages) {
		List<DroppedUri> images = new ArrayList<>();
		List<Path> files = new ArrayList<>();
		int refused = 0;
		for (String raw : uris) {
			DroppedUri uri = DroppedUri.parse(raw);
			if (uri == null) {
				continue;
			}
			if (Images.imageTypeForName(uri.path()) != null) {
				if (acceptsImages) {
					images.add(uri);
				} else {
					refused++;
				}
				continue;
			}
			if (uri.file() != null && Files.isRegularFile(uri.file())) {
				files.add(uri.file());
			}
		}
		return new Dropped(files, refused, readImageFiles(images));
	}

	/**
	 * Read picked or dropped image files: at most
	 * {@link Images#MAX_IMAGES_PER_PROMPT} per pick or drop (the composer
	 * enforces the total). Worker thread.
	 */
	private ReadImages readImageFiles(List<DroppedUri> uris) {
		List<ImageAttachment> images = new ArrayList<>();
		List<String> reasons = new ArrayList<>();
		for (DroppedUri uri : uris.subList(0, Math.min(uris.size(), Images.MAX_IMAGES_PER_PROMPT))) {
			ImageRead read = readImageFile(uri);
			if (read.ok()) {
				images.add(read.image());
			} else {
				reasons.add(read.reason());
			}
		}
		int extra = uris.size() - Images.MAX_IMAGES_PER_PROMPT;
		if (extra > 0) {
			String what = extra == 1 ? "1 more image was" : extra + " more images were";
			reasons.add(what + " not attached: a message can carry up to " + Images.MAX_IMAGES_PER_PROMPT + " images.");
		}
		return new ReadImages(images, reasons);
	}

	/**
	 * One image file as an attachment (id {@code host-img-N}): a file over
	 * {@link Images#MAX_IMAGE_BYTES} is refused from its size without being
	 * read; the bytes decide the type.
	 */
	private ImageRead readImageFile(DroppedUri uri) {
		String name = uri.name();
		Path file = uri.file();
		if (file == null) {
			return new ImageRead(false, null, name + " was not attached: only files of this computer can be read.");
		}
		try {
			if (!Files.exists(file)) {
				throw new NoSuchFileException(file.toString());
			}
			if (!Files.isRegularFile(file)) {
				return new ImageRead(false, null, name + " was not attached: it is not a file.");
			}
			long size = Files.size(file);
			if (size > Images.MAX_IMAGE_BYTES) {
				String type = Images.imageTypeForName(name);
				String tooLarge = Images.imageRejection(name, type != null ? type : "", size);
				if (tooLarge != null) {
					return new ImageRead(false, null, tooLarge);
				}
			}
			byte[] bytes = Files.readAllBytes(file);
			return Images.imageAttachmentFromBytes("host-img-" + imageSeq.incrementAndGet(), name, bytes, file.toString());
		} catch (NoSuchFileException e) {
			return new ImageRead(false, null, name + " was not attached: the file does not exist.");
		} catch (IOException | RuntimeException | OutOfMemoryError e) {
			String message = e.getMessage();
			return new ImageRead(false, null, name + " was not attached: " + (message != null && !message.isEmpty() ? message : e.toString()));
		}
	}

	/** Every refusal of a pick or a drop in one warning: in the session's pages, or in every page without one. */
	private void warn(String sessionId, List<String> reasons) {
		if (!reasons.isEmpty()) {
			controller.notice(sessionId, Severity.WARNING, String.join(" ", reasons));
		}
	}

	// ---------------------------------------------------------------------
	// Threads
	// ---------------------------------------------------------------------

	private void onController(Runnable task) {
		if (closed) {
			return;
		}
		Runnable guarded = () -> {
			if (closed) {
				return;
			}
			try {
				task.run();
			} catch (RuntimeException e) {
				host.logger().error("chat context: " + e, e);
			}
		};
		if (host.scheduler().isControllerThread()) {
			guarded.run();
		} else {
			host.scheduler().execute(guarded);
		}
	}

	private <T> CompletableFuture<T> offThread(ExecutorService worker, Supplier<T> work) {
		CompletableFuture<T> result = new CompletableFuture<>();
		try {
			worker.execute(() -> {
				try {
					result.complete(work.get());
				} catch (Throwable e) {
					result.completeExceptionally(e);
				}
			});
		} catch (RejectedExecutionException e) {
			result.completeExceptionally(e);
		}
		return result;
	}

	/** One daemon thread that ends when idle: tasks run one at a time, in the order they were handed in. */
	private static ExecutorService worker(String name) {
		ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), task -> {
			Thread thread = new Thread(task, name);
			thread.setDaemon(true);
			return thread;
		});
		executor.allowCoreThreadTimeOut(true);
		return executor;
	}

	/** Takes the handlers out again and ends the workers; the prompt composer goes back to plain text. */
	@Override
	public void close() {
		closed = true;
		subscriptions.forEach(Subscription::close);
		subscriptions.clear();
		controller.hooks().setPromptComposer(null);
		scans.shutdownNow();
		reads.shutdownNow();
	}
}
