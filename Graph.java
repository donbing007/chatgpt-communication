package com.xforceplus.cloudshell.designer.utils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 一个基于层级的简化图实现,图中没有边的概念.这是一个基于简化的DAG图的实现,其没有边的概念.<br>
 * 其有一个层次的概念, 在迭代这个图时会遵守层次不能越层迭代.像这样.
 * <pre>
 *            A     <-------l0
 *          |   |
 *          B   |   <-------l1
 *          |   |
 *          C   |   <-------l2
 *          |   |
 *            D     <-------l3
 * </pre>
 * 这里扫描的结果顺序如下.
 *  A  B  C  D, 并不是 A B D C.
 *  即虽然D的父结点是A,但是因为其和A的层级差超过1所以不会作为A的后继结点被扫描到.<br>
 *  使用时通过add或者insert增加参与者, 然后通过scan方法扫描.
 *  <pre>
 *      Participant root = // 根结点
 *      Graph graph = new Graph(root);
 *      graph.add(participant);
 *      graph.add(participant);
 *      graph.add(participant);
 *
 *      graph.scan((parents, participant, graphInner) -> {
 *          // 如果 (Collection&lt;Participatn&gt;) parents 是空的表示当前的就是根.
 *          // 处理逻辑.
 *      });
 *
 *  </pre>
 *
 * @author dongbin
 * @version 0.1 2023/4/20 16:26
 * @since 1.8
 */
public class Graph {

    private Map<Participant, Node> quickLink;

    private final Node root;

    /**
     * 构造一个图. <br />
     * 需要提供一个根参与者.
     *
     * @param participant 根参与者.
     */
    public Graph(Participant participant) {
        root = new Node(participant);

        addQuickLink(participant, root);
    }

    /**
     * 返回参与者的数量.
     *
     * @return 参与者数量.
     */
    public int size() {
        return quickLink.size();
    }

    /**
     * 返回当前层数.
     *
     * @return 层数.
     */
    public int level() {
        return quickLink.values().stream().mapToInt(n -> n.getLevel()).max().getAsInt() + 1;
    }

    /**
     * 判断是否为一个空的影响图.
     * 空的定义表示只有源头影响,没有被影响参与者.
     *
     * @return true 为空, false非空.
     */
    public boolean empty() {
        return size() == 1;
    }

    /**
     * 直接以根结点为父结点增加新的结点.
     *
     * @param targetParticipant 新的参与者.
     * @return true 成功, false 失败.
     */
    public boolean add(Participant targetParticipant) {
        return add(root.getParticipant(), targetParticipant);
    }

    /**
     * 增加参与者.<br />
     * 失败的可能性如下.
     * <ul>
     *     <li>找不到父结点.</li>
     *     <li>从父结点开始往上已经有当前参与者.</li>
     * </ul>
     *
     * @param prentParticipant  需要挂接的父参与者.
     * @param targetParticipant 需要加入的参与者.
     * @return true 加入成功, false 加入失败.
     */
    public boolean add(Participant prentParticipant, Participant targetParticipant) {
        if (prentParticipant.equals(targetParticipant)) {
            return false;
        }

        Optional<Node> optional = findQuickLink(prentParticipant);
        if (optional.isPresent()) {
            Node parentNode = optional.get();

            optional = findQuickLink(targetParticipant);
            Node newNode;
            AtomicBoolean skip = new AtomicBoolean(false);
            if (optional.isPresent()) {

                newNode = optional.get();

                // 反向迭代保证所有双亲结点不会出现相同的当前结点,保证不能成环.
                iterator(parentNode, true, false, n -> {
                    if (n == newNode) {
                        // 找到相同结点
                        skip.set(true);
                        return GraphConsumer.Action.OVER;
                    }

                    return GraphConsumer.Action.CONTINUE;
                });

            } else {

                // 新结点不会成环.
                newNode = new Node(targetParticipant);

                addQuickLink(targetParticipant, newNode);
            }

            if (!skip.get()) {
                newNode.addParent(parentNode);
                parentNode.addChild(newNode);
            } else {
                return false;
            }

            return true;
        }

        return false;
    }

    /**
     * 跳过root结点..
     *
     * @param consumer 每个参与者的处理逻辑.
     */
    public void scanNoRoot(GraphConsumer consumer) {
        scan((parent, participant, inner) -> {
            if (parent.isEmpty()) {
                return GraphConsumer.Action.CONTINUE;
            }

            return consumer.accept(parent, participant, inner);

        });
    }

    /**
     * 从影响力的源头开始扫描.
     *
     * @param consumer 每个参与者处理逻辑.
     */
    public void scan(GraphConsumer consumer) {
        scan(root.getParticipant(), consumer);
    }

    /**
     * 扫描影响图,保证每一个结点都只被扫描一次.<br />
     * 扫描是以层次方式进行.如下.
     * <pre>
     *        A
     *     |     |
     *     B     C
     *       |
     *       D
     * </pre>
     * 扫描顺序为 A B C D,即广度优先.
     *
     * @param startParticipant 开始的参与者.
     * @param consumer         每个参与者处理逻辑.
     */
    public void scan(Participant startParticipant, GraphConsumer consumer) {
        Optional<Node> pointOp = findQuickLink(startParticipant);
        if (!pointOp.isPresent()) {
            return;
        }

        Node startNode = pointOp.get();
        iterator(startNode, false, true, currentNode -> {
            Participant participant = currentNode.getParticipant();
            List<Participant> parentParticipants =
                currentNode.getParents().stream().map(n -> n.getParticipant()).collect(Collectors.toList());

            return consumer.accept(parentParticipants, participant, this);

        });
    }

    /**
     * 以树状打印出影响图.
     *
     * @return 图的字符串表示.
     */
    @Override
    public String toString() {

        StringBuffer buffer = new StringBuffer();
        iterator(root, false, false, n -> {

            if (root != n) {
                buffer.append('\n');
            }

            for (int i = 0; i < n.getLevel(); i++) {
                buffer.append("   ");
            }

            if (root != n) {
                buffer.append('L');
            }

            if (n.getLevel() > 0) {
                buffer.append("---");
            }

            buffer.append(n);

            return GraphConsumer.Action.CONTINUE;
        });

        return buffer.toString();
    }

    /**
     * 两个图的比较, 参与者数量和位置都必须严格一致才认为相等.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof Graph)) {
            return false;
        }

        if (this.size() != ((Graph) o).size()) {
            return false;
        }

        int level = this.level();
        if (level != ((Graph) o).level()) {
            return false;
        }

        final List<List<Node>> thisLevelNodes = new ArrayList<>(level);
        final List<Node> currentNodes = new LinkedList<>();
        AtomicInteger currentLevel = new AtomicInteger(0);
        iterator(this.root, false, true, node -> {
            if (node.getLevel() != currentLevel.get()) {
                thisLevelNodes.add(new ArrayList<>(currentNodes));
                currentNodes.clear();
                currentLevel.set(node.getLevel());
            }

            currentNodes.add(node);
            return GraphConsumer.Action.CONTINUE;
        });
        if (!currentNodes.isEmpty()) {
            thisLevelNodes.add(new ArrayList<>(currentNodes));
        }

        Graph other = (Graph) o;
        final List<List<Node>> otherLevelNodes = new ArrayList<>(level);
        currentLevel.set(0);
        currentNodes.clear();
        iterator(other.root, false, true, node -> {
            if (node.getLevel() != currentLevel.get()) {
                otherLevelNodes.add(new ArrayList<>(currentNodes));
                currentNodes.clear();
                currentLevel.set(node.getLevel());

            }

            currentNodes.add(node);
            return GraphConsumer.Action.CONTINUE;
        });
        if (!currentNodes.isEmpty()) {
            otherLevelNodes.add(new ArrayList<>(currentNodes));
        }

        List<Node> thisNodes;
        List<Node> otherNodes;
        for (int i = 0; i < thisLevelNodes.size(); i++) {
            thisNodes = thisLevelNodes.get(i);
            otherNodes = otherLevelNodes.get(i);

            if (!this.equalsNodes(thisNodes, otherNodes)) {
                return false;
            }
        }

        return true;
    }

    /**
     * 图的迭代.可以从指定结点正向或者逆向迭代.
     * <pre>
     *        A
     *     |     |
     *     B     C
     *       |
     *       D
     *
     * 从B开始往D方向迭代.
     * iterator(B, false, true, (n) -> LevelGraphConsumer.Action.CONTINUE);
     * 从B开始往A方向迭代.
     * iterator(B, true, true, (n) -> LevelGraphConsumer.Action.CONTINUE);
     * </pre>
     *
     * @param startNode    开始结点.
     * @param inversion    是否逆向.true 逆向, false 正向.
     * @param breadthFirst true 广度优先, false 深度优先.
     * @param consumer     结点消费器.
     */
    private static void iterator(Node startNode, boolean inversion, boolean breadthFirst, NodeConsumer consumer) {
        Deque<Node> stack = new ArrayDeque<>();
        stack.add(startNode);
        GraphConsumer.Action action;
        Node point;

        Map<Node, Object> duplicateTable = null;
        if (breadthFirst) {
            // 同一层相同的结点不能被放入栈两次,这里使用map来去重.
            // 只有广度优先时才保证不重复.
            duplicateTable = new HashMap<>();
        }

        while (!stack.isEmpty()) {
            point = stack.poll();

            action = consumer.consume(point);

            switch (action) {
                case CONTINUE: {
                    Collection<Node> nextNodes;
                    if (inversion) {
                        nextNodes = point.getParents();
                    } else {
                        nextNodes = point.getChildren();
                    }
                    if (breadthFirst) {
                        /*
                        广度优先,在队尾增加.
                        这里需要保证虽然是子结点,但是层数相差超过1即跳过.
                        原因是,需要保证按层次迭代,即0层->1层->2层....N层.
                        由于是图的关系,有可能是3层直接是1层的子结点,
                        其之所以在第3层是因为第2层也有结点是其父结点.
                                 A    0层
                                / \
                               B   \  1层
                              /     \
                             C      / 2层
                              \    /
                                 D    3层
                          其中在1层,A结点只有B一个子结点,所以广度优先时D不能作为A的子结点只能认为是C的子结点.
                          最终保证迭代顺序为 A B C D, 不可以是 A B D C D,其中D被迭代2次是错误的.
                          这是为了保证所有结点在被迭代时,其上层结点都被迭代过了.
                         */

                        int currentLevel = point.getLevel();
                        final int differenceOneLevel = 1;
                        Map<Node, Object> finalDuplicateTable = duplicateTable;
                        nextNodes.stream()
                            .filter(n -> n.getLevel() - currentLevel == differenceOneLevel)
                            .filter(n -> !finalDuplicateTable.containsKey(n))
                            .forEach(n -> {
                                stack.offer(n);
                                finalDuplicateTable.put(n, "");
                            });
                    } else {
                        /*
                        深度优先迭代,在队列首队增加.
                        和广度不一样,其迭代时不保证同一点被迭代时其上层结点都被迭代过.
                        也不保证同一结点只被迭代一次.
                         */
                        nextNodes.forEach(n -> stack.push(n));
                    }
                    break;
                }
                case OVER: {
                    return;
                }
                case OVER_SELF: {
                    break;
                }
                default: {
                    throw new IllegalArgumentException("Error action.");
                }
            }
        }
    }

    private void addQuickLink(Participant participant, Node node) {
        if (quickLink == null) {
            quickLink = new HashMap<>();
        }

        this.quickLink.put(participant, node);
    }

    private Optional<Node> findQuickLink(Participant participant) {
        if (quickLink == null) {
            return Optional.empty();
        }

        return Optional.ofNullable(this.quickLink.get(participant));
    }

    private boolean equalsNodes(List<Node> thisNodes, List<Node> otherNodes) {
        if (thisNodes.size() != otherNodes.size()) {
            return false;
        }

        Collections.sort(thisNodes, Comparator.comparing(n -> n.getParticipant().getId()));
        Collections.sort(otherNodes, Comparator.comparing(n -> n.getParticipant().getId()));

        return thisNodes.equals(otherNodes);

    }

    @FunctionalInterface
    private interface NodeConsumer {

        GraphConsumer.Action consume(Node node);
    }

    private static class Node {
        private Collection<Node> parents;
        private Collection<Node> children;

        private final Participant participant;

        private int level = 0;

        public Node(Participant participant) {
            this.participant = participant;
        }

        public void addParent(Node parent) {
            if (parents == null) {
                parents = new LinkedList<>();
            }

            if (!parents.contains(parent)) {
                parents.add(parent);

                if (parent.getLevel() >= this.level) {
                    // 让当前结点处于父结点的下层.
                    this.level = parent.getLevel() + 1;

                    updateChildLevel();
                }
            }
        }

        public void addChild(Node child) {
            if (children == null) {
                children = new LinkedList<>();
            }

            if (!children.contains(child)) {
                children.add(child);
            }
        }

        public int getLevel() {
            return level;
        }

        public Collection<Node> getParents() {
            if (this.parents == null) {
                return Collections.emptyList();
            }
            return parents;
        }

        public Collection<Node> getChildren() {
            if (this.children == null) {
                return Collections.emptyList();
            }
            return children;
        }

        public Participant getParticipant() {
            return participant;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Node)) {
                return false;
            }

            Node otherNode = (Node) o;
            return Objects.equals(this.getLevel(), otherNode.getLevel())
                && Objects.equals(this.getParticipant(), otherNode.getParticipant());
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder();

            Participant participant = getParticipant();
            sb.append("(")
                .append(participant.toString())
                .append(",")
                .append(this.level)
                .append(")");

            return sb.toString();
        }

        // 更新所有子结点层次.
        private void updateChildLevel() {
            // 不能使用广度优先遍历,因为广度优先遍历依赖level.现在正要更新leve.
            iterator(this, false, false, node -> {
                // 跳过本身.
                if (node == this) {
                    return GraphConsumer.Action.CONTINUE;
                } else {
                    int parentMaxLevel = node.getParents().stream().max(Node::compareLevel).get().getLevel();
                    // 当前结点的层次小于等于所有父结点中层次最大的那个,那么新的层次为最大的父结点层级+1.
                    if (node.getLevel() <= parentMaxLevel) {
                        node.level = parentMaxLevel + 1;
                        return GraphConsumer.Action.CONTINUE;
                    } else {
                        // 当前结点不需要更新level,所有子结点也不需要.
                        return GraphConsumer.Action.OVER_SELF;
                    }

                }
            });
        }

        // 结点的层次比较.
        public static int compareLevel(Node n0, Node n1) {
            if (n0.getLevel() < n1.getLevel()) {
                return -1;
            } else if (n0.getLevel() > n1.getLevel()) {
                return 1;
            } else {
                return 0;
            }
        }
    }
}
