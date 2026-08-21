package com.jts.gjcxfzksh.data.cache;

import lombok.extern.slf4j.Slf4j;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.SAXParserFactory;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.LongConsumer;
import java.util.zip.GZIPInputStream;

/**
 * 超大 plans 的画像专用 SAX reader。
 *
 * <p>MATSim StreamingPopulationReader 不保留 Person，却会把每个解析过的 Id
 * 永久写入进程级缓存，千万级 population 最终仍会耗尽 heap。本 reader 只保留
 * 单个人的少量字符串和聚合键，不创建任何 MATSim Person/Id。</p>
 */
@Slf4j
final class MatsimPassengerProfileRawReader {

    private static final int IO_BUFFER_BYTES = 1 << 20;

    private MatsimPassengerProfileRawReader() {
    }

    static long read(Path plansFile, MatsimPassengerProfileCache.Aggregation aggregation,
                     LongConsumer progress) {
        try (InputStream file = new BufferedInputStream(Files.newInputStream(plansFile), IO_BUFFER_BYTES);
             InputStream input = plansFile.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gz")
                     ? new GZIPInputStream(file, IO_BUFFER_BYTES) : file) {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            Handler handler = new Handler(aggregation, progress);
            var reader = factory.newSAXParser().getXMLReader();
            reader.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            reader.setContentHandler(handler);
            InputSource source = new InputSource(input);
            source.setSystemId(plansFile.toUri().toString());
            reader.parse(source);
            if (progress != null) progress.accept(handler.persons);
            return handler.persons;
        } catch (Exception e) {
            throw new RuntimeException("画像 plans 原生流式解析失败: " + plansFile + ", " + e.getMessage(), e);
        }
    }

    private static final class Handler extends DefaultHandler {
        private final MatsimPassengerProfileCache.Aggregation aggregation;
        private final LongConsumer progress;
        private final Set<String> activities = new LinkedHashSet<>();
        private final List<MatsimPassengerProfileCache.TransitUse> transitUses = new ArrayList<>();
        private final List<Integer> pendingPurposeIndexes = new ArrayList<>();
        private final StringBuilder text = new StringBuilder(256);
        private final StringBuilder attributeText = new StringBuilder(128);
        private boolean inPerson;
        private boolean inPlan;
        private boolean selectedPlan;
        private boolean capturePersonAttribute;
        private boolean capturePtRoute;
        private String attributeName = "";
        private String legMode = "";
        private Integer age;
        private long persons;

        Handler(MatsimPassengerProfileCache.Aggregation aggregation, LongConsumer progress) {
            this.aggregation = aggregation;
            this.progress = progress;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attrs) {
            switch (qName) {
                case "person" -> startPerson();
                case "plan" -> {
                    inPlan = true;
                    selectedPlan = "yes".equalsIgnoreCase(attrs.getValue("selected"));
                }
                case "attribute" -> {
                    capturePersonAttribute = inPerson && !inPlan;
                    if (capturePersonAttribute) {
                        attributeName = value(attrs, "name");
                        text.setLength(0);
                    }
                }
                case "activity" -> {
                    if (selectedPlan) acceptActivity(value(attrs, "type"));
                }
                case "leg" -> {
                    if (selectedPlan) legMode = value(attrs, "mode");
                }
                case "route" -> {
                    capturePtRoute = selectedPlan && "default_pt".equals(value(attrs, "type"))
                            && isTransitLeg(legMode);
                    if (capturePtRoute) text.setLength(0);
                }
                default -> { }
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (capturePersonAttribute || capturePtRoute) text.append(ch, start, length);
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            switch (qName) {
                case "attribute" -> finishAttribute();
                case "route" -> finishRoute();
                case "leg" -> legMode = "";
                case "plan" -> {
                    selectedPlan = false;
                    inPlan = false;
                }
                case "person" -> finishPerson();
                default -> { }
            }
        }

        private void startPerson() {
            inPerson = true;
            inPlan = false;
            selectedPlan = false;
            capturePersonAttribute = false;
            capturePtRoute = false;
            attributeName = "";
            legMode = "";
            age = null;
            activities.clear();
            transitUses.clear();
            pendingPurposeIndexes.clear();
            attributeText.setLength(0);
        }

        private void finishAttribute() {
            if (!capturePersonAttribute) return;
            String value = text.toString().trim();
            attributeText.append(attributeName).append('=').append(value).append(';');
            if ("age".equalsIgnoreCase(attributeName) || "年龄".equals(attributeName)) {
                try {
                    age = (int) Math.floor(Double.parseDouble(value));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("乘客年龄字段非法: " + value, e);
                }
            }
            capturePersonAttribute = false;
            attributeName = "";
            text.setLength(0);
        }

        private void acceptActivity(String rawType) {
            String type = rawType == null ? "" : rawType.trim().toLowerCase(Locale.ROOT);
            if (type.isBlank() || type.contains("interaction")) return;
            activities.add(type);
            for (Integer index : pendingPurposeIndexes) {
                transitUses.set(index, transitUses.get(index).withPurpose(type));
            }
            pendingPurposeIndexes.clear();
        }

        private void finishRoute() {
            if (!capturePtRoute) return;
            String json = text.toString();
            String routeId = jsonValue(json, "transitRouteId");
            if (!routeId.isBlank()) {
                MatsimPassengerProfileCache.TransitUse use = new MatsimPassengerProfileCache.TransitUse(
                        jsonValue(json, "transitLineId"), routeId,
                        jsonValue(json, "accessFacilityId"), jsonValue(json, "egressFacilityId"), null);
                pendingPurposeIndexes.add(transitUses.size());
                transitUses.add(use);
            }
            capturePtRoute = false;
            text.setLength(0);
        }

        private void finishPerson() {
            aggregation.acceptRawPerson(Set.copyOf(activities), attributeText.toString(), age,
                    List.copyOf(transitUses));
            persons++;
            if (progress != null && persons % 250_000 == 0) progress.accept(persons);
            if (persons % 1_000_000 == 0) {
                log.info("画像 plans 原生流式扫描进度: persons={}", persons);
            }
            inPerson = false;
        }
    }

    private static boolean isTransitLeg(String mode) {
        String value = mode == null ? "" : mode.toLowerCase(Locale.ROOT);
        return value.equals("pt") || value.equals("bus") || value.equals("subway")
                || value.equals("metro") || value.equals("rail") || value.equals("train")
                || value.equals("tram") || value.equals("ferry");
    }

    private static String value(Attributes attributes, String name) {
        String value = attributes == null ? null : attributes.getValue(name);
        return value == null ? "" : value;
    }

    static String jsonValue(String json, String key) {
        if (json == null || json.isBlank()) return "";
        String marker = "\"" + key + "\"";
        int index = json.indexOf(marker);
        if (index < 0) return "";
        index = json.indexOf(':', index + marker.length());
        if (index < 0) return "";
        index++;
        while (index < json.length() && Character.isWhitespace(json.charAt(index))) index++;
        if (index >= json.length() || json.charAt(index) != '"') return "";
        int start = ++index;
        StringBuilder decoded = null;
        while (index < json.length()) {
            char current = json.charAt(index);
            if (current == '"') return decoded == null ? json.substring(start, index) : decoded.toString();
            if (current == '\\' && index + 1 < json.length()) {
                if (decoded == null) decoded = new StringBuilder(json.substring(start, index));
                decoded.append(json.charAt(++index));
            } else if (decoded != null) {
                decoded.append(current);
            }
            index++;
        }
        return "";
    }
}
