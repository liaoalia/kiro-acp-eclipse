package io.github.liaoalia.kiroacp.core.session.context;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import io.github.liaoalia.kiroacp.core.pure.context.FileSearch;

/**
 * Finds the files of the workspace a query of the {@code @} picker matches:
 * what {@code workspace.findFiles} does for the reference.
 *
 * <p>The folders on disk are walked, not a model of them held by the IDE: the
 * agent creates and moves files with its own commands, and a file it made a
 * moment ago is the likeliest one to be mentioned next. The walk is breadth
 * first, so when there are more matches than are asked for, the ones kept
 * are those nearest the roots. Links to folders are not followed, symbolic
 * links and the junctions of Windows alike (a link back up would never end,
 * and one that leads out of the roots would offer files that are not the
 * workspace's); a link to a file is offered when the file is inside the roots.
 * Folders that cannot be read are passed over.
 *
 * <p>Slow: never to be called on the controller thread.
 */
public final class FileScan {

	private FileScan() {
	}

	/**
	 * @param roots          the folders to search; one inside another is searched once, as part of the outer one
	 * @param query          as typed; see {@link FileSearch#matches}
	 * @param limit          stop after this many matches
	 * @param excludedFolder folders not to enter, besides {@link FileSearch#EXCLUDED_FOLDERS}; may be {@code null}
	 * @param cancelled      asked before every folder; {@code true} ends the walk with what was found
	 * @return absolute paths of files, each once
	 */
	public static List<Path> find(List<Path> roots, String query, int limit, Predicate<Path> excludedFolder,
			BooleanSupplier cancelled) {
		List<Path> found = new ArrayList<>();
		Deque<Path[]> folders = new ArrayDeque<>();
		List<Path> outermost = outermost(roots);
		RealRoots realRoots = new RealRoots(outermost);
		for (Path root : outermost) {
			folders.add(new Path[] { root, root });
		}
		while (!folders.isEmpty() && found.size() < limit && !cancelled.getAsBoolean()) {
			Path[] next = folders.poll();
			Path folder = next[0];
			Path root = next[1];
			for (Path entry : entries(folder)) {
				BasicFileAttributes attributes = attributes(entry);
				if (attributes == null) {
					continue;
				}
				if (attributes.isDirectory()) {
					// A junction of Windows is a directory to the JDK, not a symbolic link, and "other" as well.
					if (!attributes.isOther() && !FileSearch.EXCLUDED_FOLDERS.contains(String.valueOf(entry.getFileName()))
							&& !excluded(excludedFolder, entry)) {
						folders.add(new Path[] { entry, root });
					}
				} else if ((attributes.isRegularFile() || (attributes.isSymbolicLink() && realRoots.holdFileOf(entry)))
						&& FileSearch.matches(query, root.relativize(entry).toString())) {
					found.add(entry);
					if (found.size() >= limit) {
						break;
					}
				}
			}
		}
		return found;
	}

	/** The roots that are inside no other root, normalised, each once, in the order given. */
	static List<Path> outermost(List<Path> roots) {
		List<Path> normalised = roots.stream().map(root -> root.toAbsolutePath().normalize()).distinct().toList();
		return normalised.stream()
				.filter(root -> normalised.stream().noneMatch(other -> !other.equals(root) && root.startsWith(other))).toList();
	}

	/** Where the roots really are, found out when the first link to a file asks. */
	private static final class RealRoots {
		private final List<Path> roots;
		private List<Path> real;

		RealRoots(List<Path> roots) {
			this.roots = roots;
		}

		/** @return whether the link leads to a file inside one of the roots */
		boolean holdFileOf(Path link) {
			if (!Files.isRegularFile(link)) {
				return false;
			}
			try {
				Path target = link.toRealPath();
				if (real == null) {
					real = new ArrayList<>();
					for (Path root : roots) {
						try {
							real.add(root.toRealPath());
						} catch (IOException | RuntimeException e) {
							// A root that is not there holds nothing.
						}
					}
				}
				return real.stream().anyMatch(target::startsWith);
			} catch (IOException | RuntimeException e) {
				return false;
			}
		}
	}

	private static boolean excluded(Predicate<Path> excludedFolder, Path folder) {
		try {
			return excludedFolder != null && excludedFolder.test(folder);
		} catch (RuntimeException e) {
			// Whoever knows about the folder failed to say; the walk goes on as if nobody knew.
			return false;
		}
	}

	/** The entries of a folder by name, so the same search gives the same answer twice. */
	private static List<Path> entries(Path folder) {
		List<Path> entries = new ArrayList<>();
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder)) {
			for (Path entry : stream) {
				entries.add(entry);
			}
		} catch (IOException | RuntimeException e) {
			// Gone meanwhile or not ours to read: it has no files to offer. What was listed so far still counts.
		}
		entries.sort(Comparator.comparing(entry -> String.valueOf(entry.getFileName())));
		return entries;
	}

	private static BasicFileAttributes attributes(Path entry) {
		try {
			return Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}
}
