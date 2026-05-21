package com.jts.gkcxfzksh;

import com.jts.gjcxfzksh.Application;
import com.jts.gjcxfzksh.api.model.params.DatasourceParam;
import com.jts.gjcxfzksh.api.model.params.RouteInfoParam;
import com.jts.gjcxfzksh.api.service.PTDataService;
import com.jts.gjcxfzksh.api.service.RouteService;
import com.jts.gjcxfzksh.config.MatsimConfig;
import com.jts.gjcxfzksh.data.Datasource;
import com.jts.gjcxfzksh.data.entry.Scheme;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitScheduleReader;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.*;

@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest(classes = Application.class)
public class EventLoadTest {

    @Resource
    MatsimConfig config;
    @Resource
    PTDataService dataService;
    @Resource
    RouteService routeService;

    @Test
    public void info() {
        String schemes = "public/nscard0920";
//        String schemes = "public/30wSurvey";
        Datasource.load(config.getSchemes().get(schemes));
        RouteInfoParam param = new RouteInfoParam();
        param.setRouteId("南沙1路_[广州船坞总站-蕉门公交总站]_2");
        param.setDatasource(schemes);
        long startTime = System.currentTimeMillis();
        Map<String, Object> result = routeService.routeInfo(param);
        long endTime = System.currentTimeMillis();
        log.info("Total time taken: {} ms", endTime - startTime);
        log.info(result.toString());
    }

    @Test
    public void load() {
        Map<String, Scheme> schemes = config.getSchemes();
        Scheme scheme = schemes.get("public/30wSurvey");
        Datasource.load(scheme);
        log.info("Loaded public/30wSurvey, \n {}", Datasource.data(scheme.getName()));
    }

    public TransitSchedule readTransitSchedule(String fileName) {
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        (new TransitScheduleReader(scenario)).readFile(fileName);
        return scenario.getTransitSchedule();
    }


    @Test
    public void fglTest() {
        long startTime = System.currentTimeMillis();
        Random random = new Random();
        Set<Coord> coords = new HashSet<>();
        for (int i = 0; i < 5000; i++) {
            coords.add(new Coord(12600000 + random.nextDouble(200000), 2500000 + random.nextDouble(200000)));
        }
        GeometryFactory gf = new GeometryFactory();

        // 创建所有圆的并集
        List<Polygon> circles = new ArrayList<>();
        coords.parallelStream().forEach(c -> {
            Polygon circle = gf.createPolygon(createCirclePoints(c.getX(), c.getY(), 300, 64));
            circles.add(circle);
        });

        // 合并所有圆
        Geometry union = circles.getFirst();
        for (int i = 1; i < circles.size(); i++) { // todo 合并速度过慢
            union = union.union(circles.get(i));
        }
        long endTime = System.currentTimeMillis();
        log.info("{} ms", endTime - startTime);
        log.info("area: {}", union.getArea());
//        log.info(union.toString());
    }

    private Coordinate[] createCirclePoints(double cx, double cy, double radius, int points) {
        Coordinate[] coords = new Coordinate[points + 1];
        for (int i = 0; i < points; i++) {
            double angle = 2 * Math.PI * i / points;
            double x = cx + radius * Math.cos(angle);
            double y = cy + radius * Math.sin(angle);
            coords[i] = new Coordinate(x, y);
        }
        coords[points] = coords[0]; // 闭合
        return coords;
    }

}
