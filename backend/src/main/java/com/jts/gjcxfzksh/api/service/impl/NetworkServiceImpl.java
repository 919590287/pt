package com.jts.gjcxfzksh.api.service.impl;

import com.jts.gjcxfzksh.api.common.DatasourceService;
import com.jts.gjcxfzksh.api.model.params.TileNetworkParam;
import com.jts.gjcxfzksh.api.model.pt.PTLink;
import com.jts.gjcxfzksh.api.service.NetworkService;
import com.jts.gjcxfzksh.data.MatsimData;
import com.jts.gjcxfzksh.data.cache.MatsimAnalysisCache;
import com.jts.gjcxfzksh.data.cache.MatsimPrecomputedCache;
import com.jts.gjcxfzksh.data.entry.TileNetwork;
import com.jts.gjcxfzksh.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

@Service
@Slf4j
public class NetworkServiceImpl extends DatasourceService implements NetworkService {

    @Override
    public List<PTLink> tile(TileNetworkParam param) {
        MatsimData data = matsim_data(param);
        List<Object> cached = MatsimPrecomputedCache.readNetworkTile(data, param.getZ(), param.getX(), param.getY());
        if (cached != null) {
            return (List<PTLink>) (List<?>) cached;
        }
        if (data.isLargeModel()) {
            throw new BusinessException("大模型路网瓦片缓存尚未就绪，请稍后重试");
        }
        if (MatsimAnalysisCache.isTrajectoryBuildActive()) {
            log.warn("轨迹缓存生成中，临时跳过全量路网返回: datasource={}, x={}, y={}",
                    param.getDatasource(), param.getX(), param.getY());
            return List.of();
        }

        Network network = data.getNetwork();
        Map<String, Double> linkFlows = linkFlows(data);
        int tileX = param.getX();
        int tileY = param.getY();
        if (tileX == 0 && tileY == 0 && data.getCenter() != null) {
            int[] centerTile = TileNetwork.coordInTile(data.getCenter());
            tileX = centerTile[0];
            tileY = centerTile[1];
        }

        List<Id<Link>> linkIds = tile_network(param).getTileLinks().get(tileX, tileY);
        if (linkIds == null || linkIds.isEmpty()) {
            return List.of();
        }

        List<PTLink> result = new ArrayList<>(linkIds.size());
        for (Id<Link> id : linkIds) {
            Link link = network.getLinks().get(id);
            if (link == null) {
                continue;
            }
            String linkId = link.getId().toString();
            result.add(PTLink.base(link, linkFlows.getOrDefault(linkId, 0D)));
        }
        return result;
    }

    @Override
    public List<PTLink> full(TileNetworkParam param) {
        MatsimData data = matsim_data(param);
        if (data.isLargeModel()) {
            throw new BusinessException("大模型不支持全量路网返回，请使用瓦片接口");
        }
        Network network = data.getNetwork();
        Map<String, Double> linkFlows = linkFlows(data);
        List<PTLink> result = new ArrayList<>(network.getLinks().size());
        for (Link link : network.getLinks().values()) {
            String linkId = link.getId().toString();
            result.add(PTLink.base(link, linkFlows.getOrDefault(linkId, 0D)));
        }
        return result;
    }

    private Map<String, Double> linkFlows(MatsimData data) {
        if (data.getLinkFlows() != null) {
            return data.getLinkFlows();
        }
        synchronized (data) {
            if (data.getLinkFlows() == null) {
                data.setLinkFlows(readLinkFlows(data.getOutfile().getLinkstats()));
            }
            return data.getLinkFlows();
        }
    }

    private Map<String, Double> readLinkFlows(String linkstatsPath) {
        Map<String, Double> flows = new HashMap<>();
        if (linkstatsPath == null || linkstatsPath.isBlank()) {
            return flows;
        }

        Path path = Path.of(linkstatsPath);
        if (!Files.isRegularFile(path)) {
            return flows;
        }

        try (BufferedReader reader = openReader(path)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return flows;
            }

            char delimiter = detectDelimiter(headerLine);
            String[] headers = split(headerLine, delimiter);
            int linkIndex = findLinkIndex(headers);
            List<Integer> flowIndices = findFlowIndices(headers);
            if (linkIndex < 0 || flowIndices.isEmpty()) {
                log.warn("无法识别 linkstats 流量字段: {}", linkstatsPath);
                return flows;
            }

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] values = split(line, delimiter);
                if (linkIndex >= values.length) {
                    continue;
                }

                String linkId = clean(values[linkIndex]);
                if (linkId.isBlank()) {
                    continue;
                }

                double flow = 0;
                boolean hasFlow = false;
                for (Integer flowIndex : flowIndices) {
                    if (flowIndex >= values.length) {
                        continue;
                    }
                    Double value = parseDouble(values[flowIndex]);
                    if (value != null) {
                        flow += value;
                        hasFlow = true;
                    }
                }
                if (hasFlow) {
                    flows.put(linkId, flow);
                }
            }
            log.info("读取 link 流量 {} 条: {}", flows.size(), linkstatsPath);
        } catch (Exception e) {
            log.warn("读取 link 流量失败: {}", linkstatsPath, e);
        }
        return flows;
    }

    private BufferedReader openReader(Path path) throws IOException {
        InputStream input = Files.newInputStream(path);
        if (path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gz")) {
            input = new GZIPInputStream(input);
        }
        return new BufferedReader(new InputStreamReader(input));
    }

    private char detectDelimiter(String headerLine) {
        if (headerLine.indexOf('\t') >= 0) return '\t';
        if (headerLine.indexOf(';') >= 0) return ';';
        return ',';
    }

    private String[] split(String line, char delimiter) {
        return line.split(Pattern.quote(String.valueOf(delimiter)), -1);
    }

    private int findLinkIndex(String[] headers) {
        for (int i = 0; i < headers.length; i++) {
            String header = normalizeHeader(headers[i]);
            if (header.equals("link") || header.equals("link_id") || header.equals("linkid") || header.equals("link_id_")) {
                return i;
            }
        }
        for (int i = 0; i < headers.length; i++) {
            String header = normalizeHeader(headers[i]);
            if (header.contains("link") && header.contains("id")) {
                return i;
            }
        }
        return -1;
    }

    private List<Integer> findFlowIndices(String[] headers) {
        List<Integer> exact = new ArrayList<>();
        List<Integer> hourly = new ArrayList<>();
        List<Integer> fullSpan = new ArrayList<>();
        for (int i = 0; i < headers.length; i++) {
            String header = normalizeHeader(headers[i]);
            if (header.equals("simulated_traffic_volume") || header.equals("traffic_volume") || header.equals("simulated_volume") || header.equals("flow") || header.equals("volume")) {
                exact.add(i);
            } else if (isFullSpanFlowHeader(header)) {
                fullSpan.add(i);
            } else if (isFlowSeriesHeader(header)) {
                hourly.add(i);
            }
        }
        if (!exact.isEmpty()) {
            return exact;
        }
        // MATSim CalcLinkStats 同时输出 HRS0-1avg…HRS23-24avg 逐时列和 HRS0-24avg 日汇总列，
        // 两类一起累加会得到日总量×2。有逐时列时只累加逐时列，否则用汇总列。
        // （与 MatsimPrecomputedCache.findFlowIndices 同步维护）
        return hourly.isEmpty() ? fullSpan : hourly;
    }

    /** HRS0-24avg 之类的全跨度日汇总列（跨度 ≥24 小时）。 */
    private boolean isFullSpanFlowHeader(String header) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("^hrs(\\d+)_(\\d+)avg$").matcher(header);
        if (!matcher.matches()) {
            return false;
        }
        try {
            return Integer.parseInt(matcher.group(2)) - Integer.parseInt(matcher.group(1)) >= 24;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean isFlowSeriesHeader(String header) {
        if (header.contains("capacity") || header.contains("lane") || header.contains("speed") || header.contains("length") || header.contains("coord")) {
            return false;
        }
        return header.startsWith("vol") || header.contains("_vol") || header.contains("volume") || header.contains("traffic") || header.matches("hrs\\d+_\\d+avg");
    }

    private String normalizeHeader(String value) {
        return clean(value)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    private String clean(String value) {
        if (value == null) return "";
        String result = value.replace("\uFEFF", "").trim();
        if (result.length() >= 2 && ((result.startsWith("\"") && result.endsWith("\"")) || (result.startsWith("'") && result.endsWith("'")))) {
            return result.substring(1, result.length() - 1).trim();
        }
        return result;
    }

    private Double parseDouble(String value) {
        String text = clean(value).replace(",", "");
        if (text.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
