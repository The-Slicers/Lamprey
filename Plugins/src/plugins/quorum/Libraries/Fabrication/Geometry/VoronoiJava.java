package plugins.quorum.Libraries.Fabrication.Geometry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Triangle;
import org.locationtech.jts.triangulate.IncrementalDelaunayTriangulator;
import org.locationtech.jts.triangulate.quadedge.QuadEdge;
import org.locationtech.jts.triangulate.quadedge.QuadEdgeSubdivision;
import org.locationtech.jts.triangulate.quadedge.Vertex;

/**
 * The Java side of Libraries.Fabrication.Geometry.VoronoiJava: the Voronoi
 * diagram of a layer's outline, for the adaptive walls, built with JTS
 * (pure Java, so it runs wherever Quorum does, with no native library).
 *
 * JTS diagrams points, not segments, so the outline is sampled: every corner,
 * plus points about `spacing` apart along each segment. Each sample remembers
 * the outline segment it came from, and is reported the way Boost reported a
 * cell: a reflex corner as the start point of its segment (category 1), a
 * convex corner as whichever of its two segments is nearer the edge (an exact
 * diagram's convex corner cell is empty), any other sample as its own segment
 * (category 8). SkeletalTrapezoidation then measures radii and ribs to the
 * real segment or corner, not to the sample, and splits edges with a reflex
 * corner on a side along the exact curve, as it did with Boost.
 *
 * Only the part of the diagram that is the outline's skeleton is returned:
 * - edges between two samples of the same segment, a corner and one of its
 *   own two segments, or two samples closer along the outline than the sample
 *   spacing, are dropped (they cross from the outline to the skeleton, like
 *   the "secondary" edges Boost reported and the bridge skipped);
 * - edges touching JTS's frame (unbounded edges) are dropped;
 * - edges whose midpoint or either end is outside the outline (even-odd, as
 *   SkeletalTrapezoidation tested) are dropped;
 * - circumcentres closer than 1e-7 mm are merged, and the zero-length edges
 *   this leaves are dropped;
 * - each cluster of nodes joined by edges shorter than 0.05 mm (where several
 *   branches meet) collapses into one node, as an exact diagram has it;
 * - each run of edges between the same two segments is joined into one
 *   straight edge (as an exact diagram has it), as long as the radius stays
 *   linear along it (within 0.002 mm), its nodes stay within 0.05 mm of it,
 *   and the joined edge's midpoint is inside;
 * - only vertices used by a kept edge are returned.
 * Every kept edge is returned twice, once per direction, with its cells
 * swapped: the half with cell0 on its left first, as Boost did.
 *
 * Usage: Clear, AddSegment for each outline segment (mm, in OutlineEdges
 * order), Build(spacing), then the getters. Build returns 0 on success; on
 * failure it returns a non-zero code, GetError says why, and the results are
 * empty. It never throws.
 */
public class VoronoiJava {
    public java.lang.Object me_ = null;

    /** Boost's category for a point cell at a segment's start. */
    public static final int CATEGORY_START_POINT = 1;
    /** Boost's category for a segment cell. */
    public static final int CATEGORY_SEGMENT = 8;

    public static final int OK = 0;
    public static final int BAD_SPACING = 1;
    public static final int BAD_SEGMENT = 2;
    public static final int TRIANGULATION_FAILED = 3;

    /** Circumcentres closer than this (mm) are one vertex. */
    private static final double MERGE_GRID = 1e-7;
    /** Sites closer than this (mm) are one site. */
    private static final double SITE_TOLERANCE = 1e-7;
    /** The golden ratio's fraction, to give each segment a different sample phase. */
    private static final double GOLDEN = 0.6180339887498949;

    // input segments (mm)
    private final DoubleList segX0 = new DoubleList();
    private final DoubleList segY0 = new DoubleList();
    private final DoubleList segX1 = new DoubleList();
    private final DoubleList segY1 = new DoubleList();

    // output
    private final DoubleList vertX = new DoubleList();
    private final DoubleList vertY = new DoubleList();
    private final IntList edgeCell0 = new IntList();
    private final IntList edgeCategory0 = new IntList();
    private final IntList edgeCell1 = new IntList();
    private final IntList edgeCategory1 = new IntList();
    private final IntList edgeVertex0 = new IntList();
    private final IntList edgeVertex1 = new IntList();
    private int sampleCount = 0;
    private String error = "";

    /** Removes all segments and results. */
    public void Clear() {
        segX0.clear();
        segY0.clear();
        segX1.clear();
        segY1.clear();
        clearResults();
    }

    /** Adds one outline segment, in mm. Segment indices count from 0 in the order added. */
    public void AddSegment(double x0, double y0, double x1, double y1) {
        segX0.add(x0);
        segY0.add(y0);
        segX1.add(x1);
        segY1.add(y1);
    }

    /**
     * Builds the diagram of the segments added so far, sampling each segment
     * about `spacing` mm apart. Returns OK (0), or an error code with GetError
     * set and no results.
     */
    public int Build(double spacing) {
        clearResults();
        if (!(spacing > 0.0) || Double.isInfinite(spacing)) {
            return fail(BAD_SPACING, "sample spacing must be a positive number, but was " + spacing);
        }
        int n = segX0.size();
        for (int i = 0; i < n; i++) {
            if (!finite(segX0.get(i)) || !finite(segY0.get(i)) || !finite(segX1.get(i)) || !finite(segY1.get(i))) {
                return fail(BAD_SEGMENT, "segment " + i + " has a coordinate that is not a finite number");
            }
            if (segX0.get(i) == segX1.get(i) && segY0.get(i) == segY1.get(i)) {
                return fail(BAD_SEGMENT, "segment " + i + " has length 0");
            }
        }
        if (n < 3) {
            return OK;   // nothing to enclose: no diagram, like Boost with no input
        }
        try {
            build(spacing);
            return OK;
        } catch (RuntimeException e) {
            clearResults();
            return fail(TRIANGULATION_FAILED, "triangulation failed: " + e);
        } catch (StackOverflowError | OutOfMemoryError e) {
            clearResults();
            return fail(TRIANGULATION_FAILED, "triangulation failed: " + e);
        }
    }

    public String GetError() { return error; }
    public int GetSampleCount() { return sampleCount; }
    public int GetVertexCount() { return vertX.size(); }
    public double GetVertexX(int index) { return vertX.get(index); }
    public double GetVertexY(int index) { return vertY.get(index); }
    public int GetEdgeCount() { return edgeCell0.size(); }
    public int GetEdgeCell0(int index) { return edgeCell0.get(index); }
    public int GetEdgeCategory0(int index) { return edgeCategory0.get(index); }
    public int GetEdgeCell1(int index) { return edgeCell1.get(index); }
    public int GetEdgeCategory1(int index) { return edgeCategory1.get(index); }
    public int GetEdgeVertex0(int index) { return edgeVertex0.get(index); }
    public int GetEdgeVertex1(int index) { return edgeVertex1.get(index); }

    // ------------------------------------------------------------ building

    /** A sample of the outline: a site of the point Voronoi diagram. */
    private static final class Sample extends Vertex {
        final int cell;        // the segment reported as its cell
        final int category;    // CATEGORY_START_POINT or CATEGORY_SEGMENT
        final int otherOwner;  // a corner also belongs to the segment ending there (-1: none)
        final boolean reflex;  // a corner where the outline turns away from the material (material on the left)
        int loop = -1;         // the loop it is on, and how far along it (mm)
        double arc = 0.0;

        Sample(double x, double y, int cell, int category, int otherOwner, boolean reflex) {
            super(x, y);
            this.cell = cell;
            this.category = category;
            this.otherOwner = otherOwner;
            this.reflex = reflex;
        }

        /** Distance along the outline to the other sample, if both are on the same loop (else infinity). */
        double alongOutline(Sample o, double[] perimeter) {
            if (loop < 0 || loop != o.loop) {
                return Double.POSITIVE_INFINITY;
            }
            double d = Math.abs(arc - o.arc);
            return Math.min(d, perimeter[loop] - d);
        }

        /** True if this sample and the other lie on a common outline segment. */
        boolean sharesSegmentWith(Sample o) {
            if (cell == o.cell || (otherOwner >= 0 && otherOwner == o.cell)) {
                return true;
            }
            return o.otherOwner >= 0 && (o.otherOwner == cell || o.otherOwner == otherOwner);
        }
    }

    private void build(double spacing) {
        int n = segX0.size();
        int[] previous = previousSegments();

        // the loops: each segment's loop and how far along it the segment starts
        int[] loopOf = new int[n];
        double[] startArc = new double[n];
        double[] perimeter = loops(previous, loopOf, startArc);

        // 1. sample the outline
        List<Sample> samples = new ArrayList<>();
        Envelope env = new Envelope();
        for (int i = 0; i < n; i++) {
            double x0 = segX0.get(i), y0 = segY0.get(i);
            double dx = segX1.get(i) - x0, dy = segY1.get(i) - y0;
            Sample corner = new Sample(x0, y0, i, CATEGORY_START_POINT, previous[i], isReflex(previous[i], i));
            corner.loop = loopOf[i];
            corner.arc = startArc[i];
            samples.add(corner);
            double length = Math.sqrt(dx * dx + dy * dy);
            if (length >= spacing) {
                int count = Math.max(1, (int) Math.round(length / spacing));
                // a different phase per segment, between 0.25 and 0.75 of a gap, so samples on
                // two parallel segments rarely line up four on a circle
                double phase = 0.25 + 0.5 * fraction(i * GOLDEN);
                for (int k = 0; k < count; k++) {
                    double t = (k + phase) / count;
                    Sample along = new Sample(x0 + dx * t, y0 + dy * t, i, CATEGORY_SEGMENT, -1, false);
                    along.loop = loopOf[i];
                    along.arc = startArc[i] + length * t;
                    samples.add(along);
                }
            }
            env.expandToInclude(x0, y0);
            env.expandToInclude(segX1.get(i), segY1.get(i));
        }
        sampleCount = samples.size();

        // 2. Delaunay triangulation of the samples, inserted sorted by x (JTS locates each new
        // site by walking from the last one, so nearby sites in a row are fastest). forceConvex
        // is off: it can make the triangulation non-Delaunay near the frame, which would bend
        // the Voronoi diagram.
        List<Sample> order = new ArrayList<>(samples);
        order.sort((a, b) -> a.getX() != b.getX() ? Double.compare(a.getX(), b.getX()) : Double.compare(a.getY(), b.getY()));
        QuadEdgeSubdivision subdivision = new QuadEdgeSubdivision(env, SITE_TOLERANCE);
        IncrementalDelaunayTriangulator triangulator = new IncrementalDelaunayTriangulator(subdivision);
        triangulator.forceConvex(false);
        for (Sample s : order) {
            triangulator.insertSite(s);   // a site already there (within tolerance) is skipped
        }

        // 3. one Voronoi vertex per triangle: its circumcentre (merged when they coincide).
        // Every directed edge is mapped to the circumcentre of the triangle on its left
        // (-1 for a triangle that touches the frame, or is flat).
        @SuppressWarnings("unchecked")
        java.util.Collection<QuadEdge> delaunayEdges = subdivision.getEdges();
        DoubleList cx = new DoubleList();
        DoubleList cy = new DoubleList();
        Map<Long, List<Integer>> mergeBuckets = new HashMap<>();
        IdentityHashMap<QuadEdge, Integer> leftCentre = new IdentityHashMap<>(delaunayEdges.size() * 4);
        for (QuadEdge base : delaunayEdges) {
            for (int side = 0; side < 2; side++) {
                QuadEdge e0 = side == 0 ? base : base.sym();
                if (leftCentre.containsKey(e0)) {
                    continue;
                }
                QuadEdge e1 = e0.lNext();
                QuadEdge e2 = e1.lNext();
                int index = -1;
                if (e2.lNext() == e0) {
                    Vertex a = e0.orig();
                    Vertex b = e1.orig();
                    Vertex c = e2.orig();
                    if (a instanceof Sample && b instanceof Sample && c instanceof Sample) {
                        Coordinate centre = Triangle.circumcentreDD(a.getCoordinate(), b.getCoordinate(), c.getCoordinate());
                        if (finite(centre.x) && finite(centre.y)) {
                            index = mergedIndex(centre.x, centre.y, cx, cy, mergeBuckets);
                        }
                    }
                }
                leftCentre.put(e0, index);
                if (e2.lNext() == e0) {
                    leftCentre.put(e1, index);
                    leftCentre.put(e2, index);
                }
            }
        }

        // 4. the Voronoi edge of each Delaunay edge joins the circumcentres on either side
        SegmentGrid inside = new SegmentGrid(segX0, segY0, segX1, segY1);
        byte[] nodeInside = new byte[cx.size()];     // 0 = not tested yet, 1 = inside, 2 = outside
        Set<Long> pairs = new HashSet<>();
        IntList keptFrom = new IntList();     // centre the edge starts at
        IntList keptTo = new IntList();       // centre it ends at
        IntList keptLeft = new IntList();     // cell on its left, and its category
        IntList keptLeftCategory = new IntList();
        IntList keptRight = new IntList();    // cell on its right, and its category
        IntList keptRightCategory = new IntList();
        for (QuadEdge edge : delaunayEdges) {
            Vertex o = edge.orig();
            Vertex d = edge.dest();
            if (!(o instanceof Sample) || !(d instanceof Sample)) {
                continue;
            }
            Sample p = (Sample) o;
            Sample q = (Sample) d;
            if (p.sharesSegmentWith(q) || p.alongOutline(q, perimeter) < spacing) {
                // runs from the outline to the skeleton, not along it: the two samples are on one segment, or
                // closer along the outline than the sample spacing (where the outline's segments are shorter than
                // the spacing, corners a few apart would otherwise give such spokes)
                continue;
            }
            Integer left = leftCentre.get(edge);
            Integer right = leftCentre.get(edge.sym());
            if (left == null || right == null || left < 0 || right < 0 || left.intValue() == right.intValue()) {
                continue;   // unbounded, or zero length after merging
            }
            int l = left, r = right;
            double mx = (cx.get(l) + cx.get(r)) / 2.0;
            double my = (cy.get(l) + cy.get(r)) / 2.0;
            // keep only edges wholly inside: its midpoint and both ends (where the part is narrower than the sample
            // spacing, as at a sharp tip, a sampled node can land just outside; nothing is printed that thin)
            if (!inside.contains(mx, my) || !insideNode(l, cx, cy, inside, nodeInside)
                    || !insideNode(r, cx, cy, inside, nodeInside)) {
                continue;
            }
            long pair = l < r ? ((long) l << 32) | r : ((long) r << 32) | l;
            if (!pairs.add(pair)) {
                continue;   // a second edge between the same two vertices
            }
            // going from the right circumcentre to the left one, p (the Delaunay edge's
            // origin) is on the left
            keptFrom.add(r);
            keptTo.add(l);
            keptLeft.add(cellOf(p, mx, my));
            keptLeftCategory.add(categoryOf(p));
            keptRight.add(cellOf(q, mx, my));
            keptRightCategory.add(categoryOf(q));
        }

        // 5. where several branches meet, the sampled diagram has a cluster of nodes joined by tiny edges, where an
        // exact diagram has one node: collapse each cluster (edges shorter than CLUSTER_LENGTH) into the member most
        // nearly equally far from all the cluster's sides, and drop the edges that collapse or repeat
        contractClusters(keptFrom, keptTo, keptLeft, keptLeftCategory, keptRight, keptRightCategory, cx, cy, inside);

        // 6. join each run of edges between the same two segments into one straight edge, as an exact
        // diagram has it (the Voronoi edge of two segments is straight): far fewer nodes, and edges long
        // enough for BeadTransitions' ramps
        int kept = keptFrom.size();
        IntList[] incident = new IntList[cx.size()];
        for (int k = 0; k < kept; k++) {
            attach(incident, keptFrom.get(k), k);
            attach(incident, keptTo.get(k), k);
        }
        boolean[] used = new boolean[kept];
        int[] outIndex = new int[cx.size()];
        java.util.Arrays.fill(outIndex, -1);
        for (int pass = 0; pass < 2; pass++) {
            for (int k = 0; k < kept; k++) {
                if (used[k]) {
                    continue;
                }
                // start runs at a node where they can't continue (pass 0); what's left is a closed run (pass 1)
                int startNode = keptFrom.get(k);
                if (pass == 0 && joinable(incident, startNode, keptLeft, keptLeftCategory, keptRight, keptRightCategory)) {
                    startNode = keptTo.get(k);
                    if (joinable(incident, startNode, keptLeft, keptLeftCategory, keptRight, keptRightCategory)) {
                        continue;
                    }
                }
                emitRun(startNode, k, incident, used, keptFrom, keptTo, keptLeft, keptLeftCategory,
                        keptRight, keptRightCategory, cx, cy, outIndex, inside);
            }
        }
    }

    /** Nodes joined by edges shorter than this (mm), in a group no wider than this, collapse into one node. */
    private static final double CLUSTER_LENGTH = 0.01;

    /**
     * Collapses every group of nodes joined by edges shorter than CLUSTER_LENGTH into one of its members: the one
     * whose distances to all the sides of the group's edges are most nearly equal (as an exact junction's are).
     * Rewrites the edges to use it, and drops edges that become a loop, repeat an earlier edge between the same two
     * nodes, or end up with their midpoint outside.
     */
    private void contractClusters(IntList from, IntList to, IntList left, IntList leftCategory,
                                         IntList right, IntList rightCategory, DoubleList cx, DoubleList cy,
                                         SegmentGrid inside) {
        int nodes = cx.size();
        int[] parent = new int[nodes];
        // each cluster's bounding box, on its root: a cluster never grows past CLUSTER_LENGTH across, so a run of
        // short edges along a finely tessellated outline is not swallowed whole
        double[] lowX = new double[nodes], highX = new double[nodes], lowY = new double[nodes], highY = new double[nodes];
        for (int i = 0; i < nodes; i++) {
            parent[i] = i;
            lowX[i] = highX[i] = cx.get(i);
            lowY[i] = highY[i] = cy.get(i);
        }
        for (int k = 0; k < from.size(); k++) {
            int a = from.get(k), b = to.get(k);
            double dx = cx.get(b) - cx.get(a), dy = cy.get(b) - cy.get(a);
            if (dx * dx + dy * dy < CLUSTER_LENGTH * CLUSTER_LENGTH) {
                int ra = root(parent, a), rb = root(parent, b);
                if (ra != rb) {
                    double lx = Math.min(lowX[ra], lowX[rb]), hx = Math.max(highX[ra], highX[rb]);
                    double ly = Math.min(lowY[ra], lowY[rb]), hy = Math.max(highY[ra], highY[rb]);
                    if ((hx - lx) * (hx - lx) + (hy - ly) * (hy - ly) <= CLUSTER_LENGTH * CLUSTER_LENGTH) {
                        parent[rb] = ra;
                        lowX[ra] = lx;
                        highX[ra] = hx;
                        lowY[ra] = ly;
                        highY[ra] = hy;
                    }
                }
            }
        }
        // each cluster's members, and the sides of the edges touching them
        Map<Integer, IntList> members = new HashMap<>();
        Map<Integer, Set<Long>> sides = new HashMap<>();
        boolean[] usedNode = new boolean[nodes];
        for (int k = 0; k < from.size(); k++) {
            int[] ends = {from.get(k), to.get(k)};
            for (int v : ends) {
                int r = root(parent, v);
                if (!usedNode[v]) {
                    usedNode[v] = true;
                    members.computeIfAbsent(r, x -> new IntList()).add(v);
                }
                Set<Long> s = sides.computeIfAbsent(r, x -> new HashSet<>());
                s.add(((long) left.get(k) << 8) | leftCategory.get(k));
                s.add(((long) right.get(k) << 8) | rightCategory.get(k));
            }
        }
        // each cluster's representative: the member most nearly equidistant from the sides
        int[] representative = new int[nodes];
        for (int i = 0; i < nodes; i++) {
            representative[i] = i;
        }
        for (Map.Entry<Integer, IntList> entry : members.entrySet()) {
            IntList group = entry.getValue();
            if (group.size() < 2) {
                continue;
            }
            Set<Long> groupSides = sides.get(entry.getKey());
            int best = group.get(0);
            double bestSpread = Double.POSITIVE_INFINITY;
            for (int m = 0; m < group.size(); m++) {
                int v = group.get(m);
                double low = Double.POSITIVE_INFINITY, high = 0.0;
                for (long side : groupSides) {
                    double d = sideDistance((int) (side >> 8), (int) (side & 0xff), cx.get(v), cy.get(v));
                    low = Math.min(low, d);
                    high = Math.max(high, d);
                }
                if (high - low < bestSpread) {
                    bestSpread = high - low;
                    best = v;
                }
            }
            for (int m = 0; m < group.size(); m++) {
                representative[group.get(m)] = best;
            }
        }
        // rewrite the edges
        IntList f2 = new IntList(), t2 = new IntList(), l2 = new IntList(), lc2 = new IntList(), r2 = new IntList(), rc2 = new IntList();
        Set<Long> seen = new HashSet<>();
        for (int k = 0; k < from.size(); k++) {
            int a = representative[from.get(k)], b = representative[to.get(k)];
            if (a == b) {
                continue;
            }
            long pair = a < b ? ((long) a << 32) | b : ((long) b << 32) | a;
            if (!seen.add(pair)) {
                continue;
            }
            // a node that moved can take an edge near a sharp tip outside
            if (!inside.contains((cx.get(a) + cx.get(b)) / 2.0, (cy.get(a) + cy.get(b)) / 2.0)) {
                continue;
            }
            f2.add(a);
            t2.add(b);
            l2.add(left.get(k));
            lc2.add(leftCategory.get(k));
            r2.add(right.get(k));
            rc2.add(rightCategory.get(k));
        }
        from.copyFrom(f2);
        to.copyFrom(t2);
        left.copyFrom(l2);
        leftCategory.copyFrom(lc2);
        right.copyFrom(r2);
        rightCategory.copyFrom(rc2);
    }

    private static int root(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    /** A run's nodes may sit this far (mm) off the straight edge that replaces them (sampling zigzag). */
    private static final double MAX_DEVIATION = 0.05;
    /** A run's radius may differ this much (mm) from linear along the straight edge that replaces it. */
    private static final double RADIUS_TOLERANCE = 0.002;

    /** The cell reported for a sample on an edge whose midpoint is (mx, my). */
    private int cellOf(Sample s, double mx, double my) {
        if (s.category != CATEGORY_START_POINT || s.otherOwner < 0 || s.reflex) {
            return s.cell;
        }
        // a convex corner is reported as whichever of its two segments is nearer the edge: in an exact diagram
        // a convex corner's own cell is empty, and the nodes near it are nearest one of its segments. (A reflex
        // corner stays a point, category 1: the skeleton around it is curved, and SkeletalTrapezoidation splits
        // those edges on the exact curve, as it did with Boost.)
        double here = segmentDistance2(s.cell, mx, my);
        double before = segmentDistance2(s.otherOwner, mx, my);
        return before < here ? s.otherOwner : s.cell;
    }

    private int categoryOf(Sample s) {
        if (s.category == CATEGORY_START_POINT && (s.otherOwner < 0 || s.reflex)) {
            return CATEGORY_START_POINT;
        }
        return CATEGORY_SEGMENT;
    }

    /**
     * True if the outline turns right where segment `before` meets segment `at`: with the material on the left
     * (outer loops counter-clockwise, holes clockwise, as ContourGenerator orients them) that corner is reflex.
     */
    private boolean isReflex(int before, int at) {
        if (before < 0) {
            return false;
        }
        double ax = segX1.get(before) - segX0.get(before), ay = segY1.get(before) - segY0.get(before);
        double bx = segX1.get(at) - segX0.get(at), by = segY1.get(at) - segY0.get(at);
        double cross = ax * by - ay * bx;
        double scale = Math.sqrt((ax * ax + ay * ay) * (bx * bx + by * by));
        return cross < -1e-9 * scale;
    }

    private double segmentDistance2(int i, double px, double py) {
        double ax = segX0.get(i), ay = segY0.get(i);
        double dx = segX1.get(i) - ax, dy = segY1.get(i) - ay;
        double len2 = dx * dx + dy * dy;
        double t = len2 > 0.0 ? ((px - ax) * dx + (py - ay) * dy) / len2 : 0.0;
        t = Math.max(0.0, Math.min(1.0, t));
        double ex = px - (ax + t * dx), ey = py - (ay + t * dy);
        return ex * ex + ey * ey;
    }

    private static void attach(IntList[] incident, int node, int edge) {
        if (incident[node] == null) {
            incident[node] = new IntList();
        }
        incident[node].add(edge);
    }

    /** True if node has exactly two edges, both between the same two segments: a run passes through it. */
    private static boolean joinable(IntList[] incident, int node, IntList left, IntList leftCategory,
                                    IntList right, IntList rightCategory) {
        IntList edges = incident[node];
        if (edges == null || edges.size() != 2) {
            return false;
        }
        int a = edges.get(0), b = edges.get(1);
        if (leftCategory.get(a) != CATEGORY_SEGMENT || rightCategory.get(a) != CATEGORY_SEGMENT
                || leftCategory.get(b) != CATEGORY_SEGMENT || rightCategory.get(b) != CATEGORY_SEGMENT) {
            return false;
        }
        int la = left.get(a), ra = right.get(a), lb = left.get(b), rb = right.get(b);
        return (la == lb && ra == rb) || (la == rb && ra == lb);
    }

    /** Walks the run that leaves `start` along edge `first`, and emits it as straight edges. */
    private void emitRun(int start, int first, IntList[] incident, boolean[] used,
                         IntList from, IntList to, IntList left, IntList leftCategory,
                         IntList right, IntList rightCategory, DoubleList cx, DoubleList cy, int[] outIndex,
                         SegmentGrid inside) {
        // the cells on the run's left and right, going away from start
        boolean forward = from.get(first) == start;
        int runLeft = forward ? left.get(first) : right.get(first);
        int runLeftCategory = forward ? leftCategory.get(first) : rightCategory.get(first);
        int runRight = forward ? right.get(first) : left.get(first);
        int runRightCategory = forward ? rightCategory.get(first) : leftCategory.get(first);

        IntList nodes = new IntList();
        nodes.add(start);
        int node = start;
        int edge = first;
        while (true) {
            used[edge] = true;
            node = from.get(edge) == node ? to.get(edge) : from.get(edge);
            nodes.add(node);
            if (node == start || !joinable(incident, node, left, leftCategory, right, rightCategory)) {
                break;
            }
            IntList at = incident[node];
            int next = at.get(0) == edge ? at.get(1) : at.get(0);
            if (used[next]) {
                break;
            }
            edge = next;
        }

        // straight pieces: from each kept node, go as far as the radius (the mean distance to the run's two
        // segments) stays linear along the piece, the nodes stay near and in order along it, and its midpoint stays
        // inside the outline. Between two segments the exact skeleton is straight with a linear radius, so a run
        // whose sampled nodes zigzag (where the part is narrower than the sample spacing) still joins; where a
        // segment's end is nearer than its line the radius bends, and the run is kept in pieces.
        int a = 0;
        while (a < nodes.size() - 1) {
            int b = a + 1;
            while (b + 1 < nodes.size() && withinDeviation(nodes, a, b + 1, cx, cy)
                    && radiusLinear(nodes, a, b + 1, runLeft, runRight, cx, cy)
                    && midpointInside(nodes, a, b + 1, cx, cy, inside)) {
                b++;
            }
            int v0 = outputVertex(nodes.get(a), outIndex, cx, cy);
            int v1 = outputVertex(nodes.get(b), outIndex, cx, cy);
            addEdge(runLeft, runLeftCategory, runRight, runRightCategory, v0, v1);
            addEdge(runRight, runRightCategory, runLeft, runLeftCategory, v1, v0);
            a = b;
        }
    }

    /** True if circumcentre `node` is inside the outline (tested once, remembered in `known`). */
    private static boolean insideNode(int node, DoubleList cx, DoubleList cy, SegmentGrid inside, byte[] known) {
        if (known[node] == 0) {
            known[node] = inside.contains(cx.get(node), cy.get(node)) ? (byte) 1 : (byte) 2;
        }
        return known[node] == 1;
    }

    /** Distance from (x, y) to a side: segment `cell` (category 8) or its start point (category 1). */
    private double sideDistance(int cell, int category, double x, double y) {
        if (category == CATEGORY_START_POINT) {
            double dx = x - segX0.get(cell), dy = y - segY0.get(cell);
            return Math.sqrt(dx * dx + dy * dy);
        }
        return Math.sqrt(segmentDistance2(cell, x, y));
    }

    /** Mean distance from (x, y) to segments a and b: the radius SkeletalTrapezoidation gives a node between them. */
    private double meanDistance(int a, int b, double x, double y) {
        return (Math.sqrt(segmentDistance2(a, x, y)) + Math.sqrt(segmentDistance2(b, x, y))) / 2.0;
    }

    /** True if every node strictly between positions a and b has the radius linear interpolation along a-b gives it. */
    private boolean radiusLinear(IntList nodes, int a, int b, int left, int right, DoubleList cx, DoubleList cy) {
        double ax = cx.get(nodes.get(a)), ay = cy.get(nodes.get(a));
        double dx = cx.get(nodes.get(b)) - ax, dy = cy.get(nodes.get(b)) - ay;
        double len2 = dx * dx + dy * dy;
        if (len2 == 0.0) {
            return false;
        }
        double ra = meanDistance(left, right, ax, ay);
        double rb = meanDistance(left, right, cx.get(nodes.get(b)), cy.get(nodes.get(b)));
        for (int i = a + 1; i < b; i++) {
            double px = cx.get(nodes.get(i)), py = cy.get(nodes.get(i));
            double t = ((px - ax) * dx + (py - ay) * dy) / len2;
            double expected = ra + (rb - ra) * t;
            if (Math.abs(meanDistance(left, right, px, py) - expected) > RADIUS_TOLERANCE) {
                return false;
            }
        }
        return true;
    }

    private static boolean midpointInside(IntList nodes, int a, int b, DoubleList cx, DoubleList cy, SegmentGrid inside) {
        double mx = (cx.get(nodes.get(a)) + cx.get(nodes.get(b))) / 2.0;
        double my = (cy.get(nodes.get(a)) + cy.get(nodes.get(b))) / 2.0;
        return inside.contains(mx, my);
    }

    /** True if every node strictly between positions a and b of the run is within MAX_DEVIATION of the chord a-b. */
    private static boolean withinDeviation(IntList nodes, int a, int b, DoubleList cx, DoubleList cy) {
        double ax = cx.get(nodes.get(a)), ay = cy.get(nodes.get(a));
        double dx = cx.get(nodes.get(b)) - ax, dy = cy.get(nodes.get(b)) - ay;
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len == 0.0) {
            return false;
        }
        for (int i = a + 1; i < b; i++) {
            double px = cx.get(nodes.get(i)) - ax, py = cy.get(nodes.get(i)) - ay;
            if (Math.abs(px * dy - py * dx) / len > MAX_DEVIATION) {
                return false;
            }
            // and in order along the chord, so a run never folds back on itself
            double along = (px * dx + py * dy) / len;
            if (along < 0.0 || along > len) {
                return false;
            }
        }
        return true;
    }

    /**
     * Walks the loops (segment to the segment that starts where it ends), filling each segment's loop and the
     * distance along its loop to its start; returns each loop's perimeter. A chain that doesn't close gets an
     * infinite perimeter.
     */
    private double[] loops(int[] previous, int[] loopOf, double[] startArc) {
        int n = segX0.size();
        int[] next = new int[n];
        java.util.Arrays.fill(next, -1);
        for (int i = 0; i < n; i++) {
            if (previous[i] >= 0 && next[previous[i]] < 0) {
                next[previous[i]] = i;
            }
        }
        java.util.Arrays.fill(loopOf, -1);
        DoubleList perimeters = new DoubleList();
        for (int first = 0; first < n; first++) {
            if (loopOf[first] >= 0) {
                continue;
            }
            // start an open chain at its beginning
            int start = first;
            int guard = 0;
            while (previous[start] >= 0 && previous[start] != first && loopOf[previous[start]] < 0 && guard++ < n) {
                start = previous[start];
            }
            int loop = perimeters.size();
            double arc = 0.0;
            int at = start;
            boolean closed = false;
            while (at >= 0 && loopOf[at] < 0) {
                loopOf[at] = loop;
                startArc[at] = arc;
                double dx = segX1.get(at) - segX0.get(at), dy = segY1.get(at) - segY0.get(at);
                arc += Math.sqrt(dx * dx + dy * dy);
                at = next[at];
                closed = at == start;
            }
            perimeters.add(closed ? arc : Double.POSITIVE_INFINITY);
        }
        double[] out = new double[perimeters.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = perimeters.get(i);
        }
        return out;
    }

    /** For each segment, the segment that ends where it starts (-1 if none). */
    private int[] previousSegments() {
        int n = segX0.size();
        Map<PointKey, Integer> endingAt = new HashMap<>();
        for (int i = 0; i < n; i++) {
            endingAt.put(new PointKey(segX1.get(i), segY1.get(i)), i);
        }
        int[] previous = new int[n];
        for (int i = 0; i < n; i++) {
            int before = i - 1;
            if (before >= 0 && segX1.get(before) == segX0.get(i) && segY1.get(before) == segY0.get(i)) {
                previous[i] = before;   // the usual case: segments of one loop, in order
            } else {
                Integer found = endingAt.get(new PointKey(segX0.get(i), segY0.get(i)));
                previous[i] = found == null ? -1 : found;
            }
        }
        return previous;
    }

    private int mergedIndex(double x, double y, DoubleList cx, DoubleList cy, Map<Long, List<Integer>> buckets) {
        long gx = (long) Math.floor(x / MERGE_GRID);
        long gy = (long) Math.floor(y / MERGE_GRID);
        // look in this grid cell and its neighbours, so two points either side of a cell line still merge
        for (long ix = gx - 1; ix <= gx + 1; ix++) {
            for (long iy = gy - 1; iy <= gy + 1; iy++) {
                List<Integer> bucket = buckets.get(cellKey(ix, iy));
                if (bucket == null) {
                    continue;
                }
                for (int index : bucket) {
                    double ex = cx.get(index) - x, ey = cy.get(index) - y;
                    if (ex * ex + ey * ey <= MERGE_GRID * MERGE_GRID) {
                        return index;
                    }
                }
            }
        }
        int index = cx.size();
        cx.add(x);
        cy.add(y);
        buckets.computeIfAbsent(cellKey(gx, gy), k -> new ArrayList<>()).add(index);
        return index;
    }

    private static long cellKey(long ix, long iy) {
        return ix * 73856093L ^ iy * 19349663L;
    }

    private int outputVertex(int centre, int[] outIndex, DoubleList cx, DoubleList cy) {
        if (outIndex[centre] < 0) {
            outIndex[centre] = vertX.size();
            vertX.add(cx.get(centre));
            vertY.add(cy.get(centre));
        }
        return outIndex[centre];
    }

    private void addEdge(int leftCell, int leftCategory, int rightCell, int rightCategory, int v0, int v1) {
        edgeCell0.add(leftCell);
        edgeCategory0.add(leftCategory);
        edgeCell1.add(rightCell);
        edgeCategory1.add(rightCategory);
        edgeVertex0.add(v0);
        edgeVertex1.add(v1);
    }

    private void clearResults() {
        vertX.clear();
        vertY.clear();
        edgeCell0.clear();
        edgeCategory0.clear();
        edgeCell1.clear();
        edgeCategory1.clear();
        edgeVertex0.clear();
        edgeVertex1.clear();
        sampleCount = 0;
        error = "";
    }

    private int fail(int code, String message) {
        error = message;
        return code;
    }

    private static boolean finite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }

    private static double fraction(double v) {
        return v - Math.floor(v);
    }

    // ------------------------------------------------------------ helpers

    /** An exact point, as a hash key. */
    private static final class PointKey {
        final double x, y;
        PointKey(double x, double y) { this.x = x; this.y = y; }
        @Override public boolean equals(Object o) {
            return o instanceof PointKey && ((PointKey) o).x == x && ((PointKey) o).y == y;
        }
        @Override public int hashCode() {
            long h = Double.doubleToLongBits(x) * 31 + Double.doubleToLongBits(y);
            return (int) (h ^ (h >>> 32));
        }
    }

    /**
     * Even-odd point-in-outline test over all segments (a ray toward +x, as
     * Contour:ContainsPoint casts it), with the segments sorted into horizontal
     * bands so a test only looks at the segments of its band.
     */
    static final class SegmentGrid {
        private final DoubleList x0, y0, x1, y1;
        private final double minY, bandHeight;
        private final int[][] bands;

        SegmentGrid(DoubleList x0, DoubleList y0, DoubleList x1, DoubleList y1) {
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
            int n = x0.size();
            double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < n; i++) {
                low = Math.min(low, Math.min(y0.get(i), y1.get(i)));
                high = Math.max(high, Math.max(y0.get(i), y1.get(i)));
            }
            int count = Math.max(1, Math.min(4096, n));
            minY = low;
            bandHeight = Math.max((high - low) / count, 1e-12);
            IntList[] lists = new IntList[count];
            for (int b = 0; b < count; b++) {
                lists[b] = new IntList();
            }
            for (int i = 0; i < n; i++) {
                int from = band(Math.min(y0.get(i), y1.get(i)), count);
                int to = band(Math.max(y0.get(i), y1.get(i)), count);
                for (int b = from; b <= to; b++) {
                    lists[b].add(i);
                }
            }
            bands = new int[count][];
            for (int b = 0; b < count; b++) {
                bands[b] = lists[b].toArray();
            }
        }

        private int band(double y, int count) {
            int b = (int) Math.floor((y - minY) / bandHeight);
            return Math.max(0, Math.min(count - 1, b));
        }

        boolean contains(double px, double py) {
            int b = band(py, bands.length);
            boolean inside = false;
            for (int i : bands[b]) {
                double yi = y0.get(i), yj = y1.get(i);
                if ((yi > py) != (yj > py)) {
                    double xi = x0.get(i), xj = x1.get(i);
                    double xCross = xi + (py - yi) * (xj - xi) / (yj - yi);
                    if (px < xCross) {
                        inside = !inside;
                    }
                }
            }
            return inside;
        }
    }

    /** A growable array of doubles. */
    static final class DoubleList {
        private double[] items = new double[64];
        private int size = 0;
        void add(double v) {
            if (size == items.length) {
                items = java.util.Arrays.copyOf(items, size * 2);
            }
            items[size++] = v;
        }
        double get(int i) {
            if (i < 0 || i >= size) {
                throw new IndexOutOfBoundsException("index " + i + ", size " + size);
            }
            return items[i];
        }
        void set(int i, double v) {
            if (i < 0 || i >= size) {
                throw new IndexOutOfBoundsException("index " + i + ", size " + size);
            }
            items[i] = v;
        }
        int size() { return size; }
        void clear() { size = 0; }
    }

    /** A growable array of ints. */
    static final class IntList {
        private int[] items = new int[16];
        private int size = 0;
        void add(int v) {
            if (size == items.length) {
                items = java.util.Arrays.copyOf(items, size * 2);
            }
            items[size++] = v;
        }
        int get(int i) {
            if (i < 0 || i >= size) {
                throw new IndexOutOfBoundsException("index " + i + ", size " + size);
            }
            return items[i];
        }
        int size() { return size; }
        void clear() { size = 0; }
        int[] toArray() { return java.util.Arrays.copyOf(items, size); }
        void copyFrom(IntList other) {
            items = java.util.Arrays.copyOf(other.items, Math.max(16, other.size));
            size = other.size;
        }
    }
}
