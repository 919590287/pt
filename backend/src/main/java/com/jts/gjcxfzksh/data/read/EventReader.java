package com.jts.gjcxfzksh.data.read;

import com.ctc.wstx.sax.WstxSAXParserFactory;
import lombok.extern.slf4j.Slf4j;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.*;
import org.matsim.api.core.v01.events.handler.*;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.api.experimental.events.VehicleArrivesAtFacilityEvent;
import org.matsim.core.api.experimental.events.VehicleDepartsAtFacilityEvent;
import org.matsim.core.api.experimental.events.handler.VehicleArrivesAtFacilityEventHandler;
import org.matsim.core.api.experimental.events.handler.VehicleDepartsAtFacilityEventHandler;
import org.matsim.core.events.handler.EventHandler;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.vehicles.Vehicle;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.AttributesImpl;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.SAXParser;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class EventReader extends DefaultHandler {

    private long startTime = 0;
    private final List<LinkLeaveEventHandler> linkLeaveEventHandlers = new ArrayList<>();
    private final List<LinkEnterEventHandler> linkEnterEventHandlers = new ArrayList<>();
    private final List<VehicleEntersTrafficEventHandler> vehicleEntersTrafficEventHandlers = new ArrayList<>();
    private final List<VehicleLeavesTrafficEventHandler> vehicleLeavesTrafficEventHandler = new ArrayList<>();
    private final List<PersonEntersVehicleEventHandler> personEntersVehicleEventHandlers = new ArrayList<>();
    private final List<PersonLeavesVehicleEventHandler> personLeavesVehicleEventHandlers = new ArrayList<>();
    private final List<VehicleDepartsAtFacilityEventHandler> vehicleDepartsAtFacilityEventHandlers = new ArrayList<>();
    private final List<VehicleArrivesAtFacilityEventHandler> vehicleArrivesAtFacilityEventHandlers = new ArrayList<>();
    private long current = 0L;
    private long printNum = 1L;
    private String fileName;

    public EventReader(EventHandler... ehs) {
        addEventHandler(ehs);
    }

    public void addEventHandler(EventHandler... ehs) {
        for (EventHandler eh : ehs) {
            if (eh instanceof LinkLeaveEventHandler) {
                linkLeaveEventHandlers.add((LinkLeaveEventHandler) eh);
            }
            if (eh instanceof LinkEnterEventHandler) {
                linkEnterEventHandlers.add((LinkEnterEventHandler) eh);
            }
            if (eh instanceof VehicleEntersTrafficEventHandler) {
                vehicleEntersTrafficEventHandlers.add((VehicleEntersTrafficEventHandler) eh);
            }
            if (eh instanceof VehicleLeavesTrafficEventHandler) {
                vehicleLeavesTrafficEventHandler.add((VehicleLeavesTrafficEventHandler) eh);
            }
            if (eh instanceof PersonEntersVehicleEventHandler) {
                personEntersVehicleEventHandlers.add((PersonEntersVehicleEventHandler) eh);
            }
            if (eh instanceof PersonLeavesVehicleEventHandler) {
                personLeavesVehicleEventHandlers.add((PersonLeavesVehicleEventHandler) eh);
            }
            if (eh instanceof VehicleDepartsAtFacilityEventHandler) {
                vehicleDepartsAtFacilityEventHandlers.add((VehicleDepartsAtFacilityEventHandler) eh);
            }
            if (eh instanceof VehicleArrivesAtFacilityEventHandler) {
                vehicleArrivesAtFacilityEventHandlers.add((VehicleArrivesAtFacilityEventHandler) eh);
            }
        }
    }

    public void read(String xml) throws Exception {
        fileName = xml.substring(xml.lastIndexOf("\\") + 1);
        long time = System.currentTimeMillis();
        // SAX读取
        WstxSAXParserFactory factory = new WstxSAXParserFactory();
        SAXParser parser = factory.newSAXParser();
        XMLReader reader = parser.getXMLReader();
        reader.setContentHandler(this);
        reader.parse(new InputSource(IOUtils.getBufferedReader(xml)));
        log.info("解析耗时:{}ms", (System.currentTimeMillis() - time));
    }


    @Override
    public void startDocument() {
        log.info("开始解析：{}", fileName);
        startTime = System.currentTimeMillis();
    }

    @Override
    public void endDocument() {
        log.info("完成解析，耗时 {}ms", (System.currentTimeMillis() - startTime));
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes) {
        current++;
        if (current % printNum == 0) {
            printNum *= 4;
            log.info(" event # {}", current);
        }

        String eventType = attributes.getValue(Event.ATTRIBUTE_TYPE); // type
        if (eventType == null) {
            return;
        }
        double time = Double.parseDouble(attributes.getValue(Event.ATTRIBUTE_TIME)); // time

        // 只触发用到的事件
        switch (eventType) {
            case LinkLeaveEvent.EVENT_TYPE: {
                this.handler(new LinkLeaveEvent(time,
                        Id.create(attributes.getValue(LinkLeaveEvent.ATTRIBUTE_VEHICLE), Vehicle.class),
                        Id.create(attributes.getValue(LinkLeaveEvent.ATTRIBUTE_LINK), Link.class)
                ));
                break;
            }
            case LinkEnterEvent.EVENT_TYPE: {
                this.handler(new LinkEnterEvent(time,
                        Id.create(attributes.getValue(LinkEnterEvent.ATTRIBUTE_VEHICLE), Vehicle.class),
                        Id.create(attributes.getValue(LinkEnterEvent.ATTRIBUTE_LINK), Link.class)));
                break;
            }
            case VehicleEntersTrafficEvent.EVENT_TYPE: {
                this.handler(new VehicleEntersTrafficEvent(time,
                        Id.create(attributes.getValue(HasPersonId.ATTRIBUTE_PERSON), Person.class),
                        Id.create(attributes.getValue(VehicleEntersTrafficEvent.ATTRIBUTE_LINK), Link.class),
                        Id.create(attributes.getValue(VehicleEntersTrafficEvent.ATTRIBUTE_VEHICLE), Vehicle.class),
                        attributes.getValue(VehicleEntersTrafficEvent.ATTRIBUTE_NETWORKMODE),
                        Double.parseDouble(attributes.getValue(VehicleEntersTrafficEvent.ATTRIBUTE_POSITION))
                ));
                break;
            }
            case VehicleLeavesTrafficEvent.EVENT_TYPE: {
                this.handler(new VehicleLeavesTrafficEvent(time,
                        Id.create(attributes.getValue(VehicleLeavesTrafficEvent.ATTRIBUTE_DRIVER), Person.class),
                        Id.create(attributes.getValue(VehicleLeavesTrafficEvent.ATTRIBUTE_LINK), Link.class),
                        attributes.getValue(VehicleLeavesTrafficEvent.ATTRIBUTE_VEHICLE) == null ? null : Id.create(attributes.getValue(VehicleLeavesTrafficEvent.ATTRIBUTE_VEHICLE), Vehicle.class),
                        attributes.getValue(VehicleLeavesTrafficEvent.ATTRIBUTE_NETWORKMODE),
                        Double.parseDouble(attributes.getValue(VehicleLeavesTrafficEvent.ATTRIBUTE_POSITION))
                ));
                break;
            }
            case PersonEntersVehicleEvent.EVENT_TYPE: {
                String personString = attributes.getValue(PersonEntersVehicleEvent.ATTRIBUTE_PERSON);
                String vehicleString = attributes.getValue(PersonEntersVehicleEvent.ATTRIBUTE_VEHICLE);
                this.handler(new PersonEntersVehicleEvent(time, Id.create(personString, Person.class), Id.create(vehicleString, Vehicle.class)));
                break;
            }
            case PersonLeavesVehicleEvent.EVENT_TYPE: {
                Id<Person> pId = Id.create(attributes.getValue(PersonLeavesVehicleEvent.ATTRIBUTE_PERSON), Person.class);
                Id<Vehicle> vId = Id.create(attributes.getValue(PersonLeavesVehicleEvent.ATTRIBUTE_VEHICLE), Vehicle.class);
                this.handler(new PersonLeavesVehicleEvent(time, pId, vId));
                break;
            }
            case VehicleDepartsAtFacilityEvent.EVENT_TYPE: {
                String delay = attributes.getValue(VehicleDepartsAtFacilityEvent.ATTRIBUTE_DELAY);
                this.handler(new VehicleDepartsAtFacilityEvent(time, Id.create(attributes.getValue(VehicleArrivesAtFacilityEvent.ATTRIBUTE_VEHICLE), Vehicle.class), Id.create(attributes.getValue(VehicleArrivesAtFacilityEvent.ATTRIBUTE_FACILITY), TransitStopFacility.class), delay == null ? 0.0 : Double.parseDouble(delay)));
                break;
            }
            case VehicleArrivesAtFacilityEvent.EVENT_TYPE: {
                String delay = attributes.getValue(VehicleArrivesAtFacilityEvent.ATTRIBUTE_DELAY);
                this.handler(new VehicleArrivesAtFacilityEvent(time, Id.create(attributes.getValue(VehicleArrivesAtFacilityEvent.ATTRIBUTE_VEHICLE), Vehicle.class), Id.create(attributes.getValue(VehicleArrivesAtFacilityEvent.ATTRIBUTE_FACILITY), TransitStopFacility.class), delay == null ? 0.0 : Double.parseDouble(delay)));
                break;
            }
        }
    }


    public void handler(LinkLeaveEvent event) {
        for (LinkLeaveEventHandler handler : linkLeaveEventHandlers) {
            handler.handleEvent(event);
        }
    }

    public void handler(LinkEnterEvent event) {
        for (LinkEnterEventHandler handler : linkEnterEventHandlers) {
            handler.handleEvent(event);
        }
    }

    public void handler(VehicleEntersTrafficEvent event) {
        for (VehicleEntersTrafficEventHandler handler : vehicleEntersTrafficEventHandlers) {
            handler.handleEvent(event);
        }
    }

    public void handler(VehicleLeavesTrafficEvent event) {
        for (VehicleLeavesTrafficEventHandler handler : vehicleLeavesTrafficEventHandler) {
            handler.handleEvent(event);
        }
    }

    public void handler(PersonEntersVehicleEvent event) {
        for (PersonEntersVehicleEventHandler handler : personEntersVehicleEventHandlers) {
            handler.handleEvent(event);
        }
    }

    public void handler(PersonLeavesVehicleEvent event) {
        for (PersonLeavesVehicleEventHandler handler : personLeavesVehicleEventHandlers) {
            handler.handleEvent(event);
        }
    }

    public void handler(VehicleDepartsAtFacilityEvent event) {
        for (VehicleDepartsAtFacilityEventHandler handler : vehicleDepartsAtFacilityEventHandlers) {
            handler.handleEvent(event);
        }
    }

    public void handler(VehicleArrivesAtFacilityEvent event) {
        for (VehicleArrivesAtFacilityEventHandler handler : vehicleArrivesAtFacilityEventHandlers) {
            handler.handleEvent(event);
        }
    }


    private static final char space_char = ' ';
    private static final char amount_char = '=';
    private static final String empty_char = "";

    // 封装xml attribute
    private static void setAttributes(AttributesImpl attributes, String line) {
        int spaceIndex = line.indexOf(space_char);
        int amountIndex = line.indexOf(amount_char);
        while (amountIndex > -1) {
            String key = line.substring(spaceIndex, amountIndex).trim();
            line = line.substring(amountIndex + 2);

            spaceIndex = line.indexOf('"');
            String value = line.substring(0, spaceIndex);
            line = line.substring(spaceIndex + 2);

            spaceIndex = 0;
            amountIndex = line.indexOf(amount_char);
            attributes.addAttribute(empty_char, empty_char, key, empty_char, value);
        }
    }

}
