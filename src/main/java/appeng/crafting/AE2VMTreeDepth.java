package appeng.crafting;

/**
 * Recursion depth counter for the native crafting tree.
 *
 * AE2UEL's CraftingTreeNode.request recursion has no cycle guard: with a
 * net-amplifying self-loop pattern in the registry (exactly the shape the
 * ring solver exists to handle), ANY native expansion — a fallback root or
 * a pattern-less key whose subtree reaches the loop — recurses until the
 * thread dies with StackOverflowError. AE2UEL's job wrapper then swallows
 * the error, prints "Error: ..." into chat, and the confirm window never
 * opens.
 *
 * The guard mixin counts request() frames per thread; past the limit the
 * deepest branch fails as an honest missing item (CraftBranchFailure), so
 * the confirm screen renders with a missing list instead of the server
 * thread dying. The counter self-heals at every CraftingJob.run() head and
 * at every VMRootNode hand-off (both reset explicitly), which bounds the
 * leak from exception exits that bypass the return hook.
 *
 * Must live in appeng.crafting: CraftBranchFailure is package-private.
 */
public final class AE2VMTreeDepth {
    /** Deep enough for every legitimate recipe web we know of. */
    public static final int LIMIT = 512;

    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    private AE2VMTreeDepth() {
    }

    /** @return the depth after the increment. */
    public static int enter() {
        int[] d = DEPTH.get();
        return ++d[0];
    }

    public static void exit() {
        int[] d = DEPTH.get();
        if (d[0] > 0) {
            d[0]--;
        }
    }

    public static void reset() {
        DEPTH.get()[0] = 0;
    }
}
