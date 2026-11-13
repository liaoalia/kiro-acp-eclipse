package io.github.liaoalia.kiroacp.ui.context;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Predicate;

import org.eclipse.core.filesystem.URIUtil;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;

/**
 * The folders the {@code @} file search leaves out because of what Eclipse
 * knows about them: derived folders (what a builder writes: {@code bin},
 * {@code target}), team-private ones (a version control system's own:
 * {@code .git} under EGit, {@code .svn}, {@code CVS}) and hidden ones.
 *
 * <p>The reference excludes {@code node_modules}, {@code .git}, {@code dist}
 * and {@code out} by name: where build output and a repository's innards are
 * found in its world. Those names are still excluded; these flags say the
 * same of a folder in the terms of this IDE, whatever it is called.
 *
 * <p>Only what the resource tree knows is asked, in memory: a folder it has
 * not seen yet is not excluded. Callable from any thread.
 */
public final class ResourceExclusions implements Predicate<Path> {

	private final IWorkspaceRoot root;

	public ResourceExclusions() {
		this(ResourcesPlugin.getWorkspace().getRoot());
	}

	ResourceExclusions(IWorkspaceRoot root) {
		this.root = Objects.requireNonNull(root, "root");
	}

	@Override
	public boolean test(Path folder) {
		// A folder of a project nested in another is a resource of both; either may know.
		// Asked for by name: the flags that exclude a folder here also hide it from a plain lookup.
		for (IContainer container : root.findContainersForLocationURI(
				URIUtil.toURI(org.eclipse.core.runtime.Path.fromOSString(folder.toString())),
				IContainer.INCLUDE_TEAM_PRIVATE_MEMBERS | IContainer.INCLUDE_HIDDEN)) {
			if (container.exists() && (container.isDerived() || container.isTeamPrivateMember() || container.isHidden())) {
				return true;
			}
		}
		return false;
	}
}
