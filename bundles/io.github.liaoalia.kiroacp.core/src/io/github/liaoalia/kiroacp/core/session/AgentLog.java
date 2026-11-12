package io.github.liaoalia.kiroacp.core.session;

import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.liaoalia.kiroacp.core.host.Logger;

/**
 * The logger an agent process gets: the host's, plus an ear on the agent's
 * stderr. kiro-cli reports its backend failures only there
 * ({@code [ERROR] [KRS] HTTP 407 …}) while the turn may stay open, so those
 * lines become notices (reference D27).
 */
final class AgentLog implements Logger {

	private static final Pattern ERROR = Pattern.compile("\\[ERROR\\]\\s*(?:\\[[^\\]]*\\]\\s*)?(.*)", Pattern.DOTALL);
	private static final Pattern HTTP = Pattern.compile("HTTP\\s+(\\d{3})(?:.*?<TITLE>([^<]*)</TITLE>)?",
			Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
	private static final Pattern BODY = Pattern.compile("\\s*body=.*$", Pattern.DOTALL);

	private final Logger delegate;
	private final Consumer<String> stderr;

	/** @param stderr gets every stderr line, on the thread that read it */
	AgentLog(Logger delegate, Consumer<String> stderr) {
		this.delegate = delegate;
		this.stderr = stderr;
	}

	/**
	 * @return what an {@code [ERROR]} line of kiro-cli says, short enough for a notice; {@code null}
	 *         for any other line
	 */
	static String errorDetail(String line) {
		Matcher error = ERROR.matcher(line);
		if (!error.find()) {
			return null;
		}
		String detail = error.group(1).trim();
		Matcher http = HTTP.matcher(detail);
		if (http.find()) {
			String title = http.group(2);
			detail = "HTTP " + http.group(1) + (title != null && !title.isEmpty() ? " (" + title.trim() + ")" : "");
		} else {
			detail = BODY.matcher(detail).replaceFirst("");
			detail = detail.substring(0, Math.min(160, detail.length()));
		}
		return detail.isEmpty() ? null : detail;
	}

	@Override
	public void info(String message) {
		delegate.info(message);
	}

	@Override
	public void warn(String message) {
		delegate.warn(message);
	}

	@Override
	public void error(String message, Throwable cause) {
		delegate.error(message, cause);
	}

	/** Always: the process only hands over its stderr to a logger that traces. */
	@Override
	public boolean traces() {
		return true;
	}

	@Override
	public void trace(Direction direction, String payload) {
		if (delegate.traces()) {
			delegate.trace(direction, payload);
		}
		if (direction == Direction.STDERR) {
			stderr.accept(payload);
		}
	}
}
