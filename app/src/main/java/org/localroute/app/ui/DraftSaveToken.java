package org.localroute.app.ui;

/**
 * 一次「保存草稿」的身份令牌（R32-06）。
 *
 * <p>0.3.2 只比较 {@code editRevision}，而 {@code load()} / {@code newDraft()} 并不递增它。于是
 * 「保存 A 的过程中切到 B（还没有任何编辑）」时，旧回调仍然会匹配，把 B 也标成「已保存」——
 * B 的 dirty 被清掉，用户以为存过了。
 *
 * <p>令牌把三样东西绑在一起：
 * <ul>
 *   <li>{@code routeId}：存的是哪条路线；</li>
 *   <li>{@code draftGeneration}：草稿被整体替换（加载/新建/删除）的次数；</li>
 *   <li>{@code editRevision}：草稿内的编辑次数。</li>
 * </ul>
 * 三者全部一致才算「保存的就是当前草稿」。
 */
public final class DraftSaveToken {

    private final String routeId;
    private final long draftGeneration;
    private final long editRevision;

    public DraftSaveToken(String routeId, long draftGeneration, long editRevision) {
        this.routeId = routeId;
        this.draftGeneration = draftGeneration;
        this.editRevision = editRevision;
    }

    public String routeId() {
        return routeId;
    }

    public long draftGeneration() {
        return draftGeneration;
    }

    public long editRevision() {
        return editRevision;
    }

    /** 与「当前草稿」的这三个值是否完全一致。 */
    public boolean matches(String otherRouteId, long otherGeneration, long otherRevision) {
        if (draftGeneration != otherGeneration || editRevision != otherRevision) {
            return false;
        }
        return routeId == null ? otherRouteId == null : routeId.equals(otherRouteId);
    }

    public String describe() {
        return "route=" + (routeId == null ? "-" : routeId)
                + " gen=" + draftGeneration + " rev=" + editRevision;
    }
}
