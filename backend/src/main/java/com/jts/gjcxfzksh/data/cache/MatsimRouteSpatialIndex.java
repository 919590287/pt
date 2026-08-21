package com.jts.gjcxfzksh.data.cache;

import com.jts.gjcxfzksh.api.model.pt.PTCoord;
import com.jts.gjcxfzksh.api.model.vo.RoutePickVO;
import com.jts.gjcxfzksh.data.MatsimData;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.index.strtree.STRtree;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Spatial lookup from an exact model-network segment to the transit routes using it. */
public final class MatsimRouteSpatialIndex {

    private static final BackendMemoryCache<MatsimData, Index> CACHE =
            new BackendMemoryCache<>("route-spatial-index", 128L * 1024 * 1024, Index::estimatedBytes);

    private MatsimRouteSpatialIndex() {
    }

    public static void prepareOnModelLoad(MatsimData data) {
        index(data);
    }

    /** Release the strong model reference as soon as the model returns to CATALOG. */
    public static void release(MatsimData data) {
        if (data != null) CACHE.remove(data);
    }

    public static List<RoutePickVO> query(MatsimData data, double x, double y, double radiusMeters, int limit) {
        double radius = Math.max(5.0, Math.min(1000.0, radiusMeters));
        int maxResults = Math.max(1, Math.min(100, limit));
        return index(data).query(x, y, radius, maxResults);
    }

    private static Index index(MatsimData data) {
        return CACHE.computeIfAbsent(data, Index::new);
    }

    private record RouteMeta(
            String lineId,
            String lineName,
            String routeId,
            String routeName,
            String startName,
            String endName
    ) {
        String key() {
            return routeKey(lineId, routeId);
        }
    }

    private record LinkMeta(Link link, List<RouteMeta> routes) {
    }

    private record Match(RouteMeta route, double distance, Link link) {
        RoutePickVO toVO() {
            return new RoutePickVO(
                    route.lineId,
                    route.lineName,
                    route.routeId,
                    route.routeName,
                    route.startName,
                    route.endName,
                    distance,
                    new PTCoord(link.getFromNode().getCoord()),
                    new PTCoord(link.getToNode().getCoord())
            );
        }
    }

    private static final class Index {
        private final STRtree links = new STRtree();
        private final long estimatedBytes;

        private Index(MatsimData data) {
            Network network = data.getNetwork();
            Map<String, List<RouteMeta>> routesByLink = new HashMap<>();
            int routeCount = 0;

            for (Map.Entry<Id<TransitLine>, TransitLine> lineEntry : data.getSchedule().getTransitLines().entrySet()) {
                TransitLine line = lineEntry.getValue();
                String lineId = lineEntry.getKey().toString();
                String lineName = nonBlank(line.getName(), lineId);
                for (Map.Entry<Id<TransitRoute>, TransitRoute> routeEntry : line.getRoutes().entrySet()) {
                    TransitRoute route = routeEntry.getValue();
                    String routeId = routeEntry.getKey().toString();
                    List<TransitRouteStop> stops = route.getStops();
                    RouteMeta meta = new RouteMeta(
                            lineId,
                            lineName,
                            routeId,
                            nonBlank(route.getDescription(), routeId),
                            stops.isEmpty() ? "" : stationName(stops.getFirst()),
                            stops.isEmpty() ? "" : stationName(stops.getLast())
                    );
                    routeCount++;
                    if (!(route.getRoute() instanceof NetworkRoute networkRoute)) continue;
                    Set<String> routeLinkIds = new HashSet<>();
                    addLinkId(routeLinkIds, networkRoute.getStartLinkId());
                    networkRoute.getLinkIds().forEach(id -> addLinkId(routeLinkIds, id));
                    addLinkId(routeLinkIds, networkRoute.getEndLinkId());
                    for (String linkId : routeLinkIds) {
                        routesByLink.computeIfAbsent(linkId, ignored -> new ArrayList<>()).add(meta);
                    }
                }
            }

            long routeAssociations = 0L;
            int indexedLinks = 0;
            for (Map.Entry<String, List<RouteMeta>> entry : routesByLink.entrySet()) {
                Link link = network.getLinks().get(Id.createLinkId(entry.getKey()));
                if (link == null) continue;
                double x1 = link.getFromNode().getCoord().getX();
                double y1 = link.getFromNode().getCoord().getY();
                double x2 = link.getToNode().getCoord().getX();
                double y2 = link.getToNode().getCoord().getY();
                links.insert(new Envelope(x1, x2, y1, y2), new LinkMeta(link, List.copyOf(entry.getValue())));
                indexedLinks++;
                routeAssociations += entry.getValue().size();
            }
            links.build();
            // STRtree/Envelope/LinkMeta and immutable route lists are custom objects which the generic
            // collection estimator cannot inspect. Keep a deliberately conservative per-item estimate.
            estimatedBytes = 256L * 1024
                    + indexedLinks * 320L
                    + routeAssociations * 128L
                    + routeCount * 384L;
        }

        private long estimatedBytes() {
            return estimatedBytes;
        }

        @SuppressWarnings("unchecked")
        private List<RoutePickVO> query(double x, double y, double radius, int limit) {
            Envelope search = new Envelope(x - radius, x + radius, y - radius, y + radius);
            List<LinkMeta> nearby = links.query(search);
            Map<String, Match> matches = new LinkedHashMap<>();
            for (LinkMeta item : nearby) {
                Link link = item.link();
                double distance = pointSegmentDistance(
                        x,
                        y,
                        link.getFromNode().getCoord().getX(),
                        link.getFromNode().getCoord().getY(),
                        link.getToNode().getCoord().getX(),
                        link.getToNode().getCoord().getY()
                );
                if (distance > radius) continue;
                for (RouteMeta route : item.routes()) {
                    Match previous = matches.get(route.key());
                    if (previous == null || distance < previous.distance()) {
                        matches.put(route.key(), new Match(route, distance, link));
                    }
                }
            }
            return matches.values().stream()
                    .sorted(Comparator.comparingDouble(Match::distance).thenComparing(match -> match.route().lineName()))
                    .limit(limit)
                    .map(Match::toVO)
                    .toList();
        }
    }

    private static void addLinkId(Set<String> result, Id<Link> id) {
        if (id != null) result.add(id.toString());
    }

    private static String stationName(TransitRouteStop stop) {
        return nonBlank(stop.getStopFacility().getName(), stop.getStopFacility().getId().toString());
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String routeKey(String lineId, String routeId) {
        return nonBlank(lineId, "") + "::" + nonBlank(routeId, "");
    }

    private static double pointSegmentDistance(double px, double py, double ax, double ay, double bx, double by) {
        double dx = bx - ax;
        double dy = by - ay;
        double lengthSquared = dx * dx + dy * dy;
        if (lengthSquared <= 0) return Math.hypot(px - ax, py - ay);
        double t = Math.max(0.0, Math.min(1.0, ((px - ax) * dx + (py - ay) * dy) / lengthSquared));
        return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
    }
}
