package com.jts.gjcxfzksh.api.model.pt;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.matsim.api.core.v01.network.Link;

@Data
public class PTLink {

    String linkId;
    PTCoord from;
    PTCoord to;
    Double flow;
    Double length;
    Double lanes;

    public static PTLink base(Link link) {
        return base(link, null);
    }

    public static PTLink base(Link link, Double flow) {
        BaseImpl base = new BaseImpl();
        base.setLinkId(link.getId().toString());
        base.setFrom(new PTCoord(link.getFromNode().getCoord()));
        base.setTo(new PTCoord(link.getToNode().getCoord()));
        base.setFlow(flow);
        base.setLength(link.getLength());
        base.setLanes(link.getNumberOfLanes());
        return base;
    }

    public static PTLink all(Link link) {
        AllImpl base = new AllImpl();
        base.setLinkId(link.getId().toString());
        return base;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    private static class BaseImpl extends PTLink {
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    private static class AllImpl extends BaseImpl {

    }

}
