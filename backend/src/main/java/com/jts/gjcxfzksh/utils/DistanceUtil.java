package com.jts.gjcxfzksh.utils;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.routes.NetworkRoute;

public class DistanceUtil {

    public static double distance(Link link) {
        return distance(link.getFromNode().getCoord(), link.getToNode().getCoord());
    }

    public static double distance(NetworkRoute networkRoute, Network network) {
        double distance = 0;
        Link start = network.getLinks().get(networkRoute.getStartLinkId());
        distance += NetworkUtils.getEuclideanDistance(start.getFromNode().getCoord(), start.getToNode().getCoord());
        for (Id<Link> linkId : networkRoute.getLinkIds()) {
            Link link = network.getLinks().get(linkId);
            distance += NetworkUtils.getEuclideanDistance(link.getFromNode().getCoord(), link.getToNode().getCoord());
        }
        Link end = network.getLinks().get(networkRoute.getEndLinkId());
        distance += NetworkUtils.getEuclideanDistance(end.getFromNode().getCoord(), end.getToNode().getCoord());
        return distance;
    }

    public static double distance(Coord c1, Coord c2) {
        if (c1.hasZ() && c2.hasZ()) {
            return distance(c1.getX(), c1.getY(), c1.getZ(), c2.getX(), c2.getY(), c2.getZ());
        }
        return distance(c1.getX(), c1.getY(), c2.getX(), c2.getY());
    }

    public static double distance(double x1, double y1, double x2, double y2) {
        return distance(x1, y1, 0, x2, y2, 0);
    }

    public static double distance(double x1, double y1, double z1, double x2, double y2, double z2) {
        return NetworkUtils.getEuclideanDistance(new Coord(x1, y1, z1), new Coord(x2, y2, z2));
    }

}
