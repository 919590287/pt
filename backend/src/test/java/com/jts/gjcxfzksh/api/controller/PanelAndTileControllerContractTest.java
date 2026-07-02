package com.jts.gjcxfzksh.api.controller;

import com.jts.gjcxfzksh.api.model.params.RouteInfoParam;
import com.jts.gjcxfzksh.api.model.params.TileNetworkParam;
import com.jts.gjcxfzksh.api.model.pt.PTCoord;
import com.jts.gjcxfzksh.api.model.pt.PTLink;
import com.jts.gjcxfzksh.api.service.FacilityService;
import com.jts.gjcxfzksh.api.service.RouteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PanelAndTileControllerContractTest {

    private RouteService routeService;
    private FacilityService facilityService;
    private MockMvc routeMvc;
    private MockMvc facilityMvc;

    @BeforeEach
    void setUp() {
        routeService = mock(RouteService.class);
        facilityService = mock(FacilityService.class);

        RouteController routeController = new RouteController();
        ReflectionTestUtils.setField(routeController, "routeService", routeService);
        routeMvc = MockMvcBuilders.standaloneSetup(routeController).build();

        FacilityController facilityController = new FacilityController();
        ReflectionTestUtils.setField(facilityController, "facilityService", facilityService);
        facilityMvc = MockMvcBuilders.standaloneSetup(facilityController).build();
    }

    @Test
    void routePanelDetailPassesLineIdToDisambiguateDuplicateRouteIds() throws Exception {
        when(routeService.routePanelDetail(any(RouteInfoParam.class))).thenReturn(Map.of(
                "lineId", "metro-line",
                "routeId", "shared",
                "mode", "subway",
                "metrics", Map.of("passenger", 12)
        ));

        routeMvc.perform(post("/pt/route/routePanelDetail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"datasource\":\"area/public/model\",\"lineId\":\"metro-line\",\"routeId\":\"shared\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.lineId").value("metro-line"))
                .andExpect(jsonPath("$.data.routeId").value("shared"))
                .andExpect(jsonPath("$.data.metrics.passenger").value(12));

        ArgumentCaptor<RouteInfoParam> param = ArgumentCaptor.forClass(RouteInfoParam.class);
        verify(routeService).routePanelDetail(param.capture());
        assertEquals("metro-line", param.getValue().getLineId());
        assertEquals("shared", param.getValue().getRouteId());
    }

    @Test
    void stationPanelReturnsGeneratingPayloadWithoutControllerSideMutation() throws Exception {
        when(facilityService.stationPanel(any())).thenReturn(Map.of(
                "status", "generating",
                "cacheVersion", "station-panel-v10",
                "message", "站点客流缓存正在后台生成"
        ));

        facilityMvc.perform(post("/pt/facility/stationPanel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"datasource\":\"area/public/model\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("generating"))
                .andExpect(jsonPath("$.data.cacheVersion").value("station-panel-v10"));
    }

    @Test
    void routeTileBinaryEncodesLinksAsStableLittleEndianBinaryContract() throws Exception {
        PTLink link = new PTLink();
        link.setLinkId("link-1");
        link.setFrom(new PTCoord(100.0, 200.0));
        link.setTo(new PTCoord(115.5, 210.25));
        link.setFlow(7.0);
        link.setLength(42.5);
        link.setLanes(2.0);
        when(routeService.routeTile(any(TileNetworkParam.class))).thenReturn(List.of(link));

        byte[] body = routeMvc.perform(post("/pt/route/tile.bin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"datasource\":\"area/public/model\",\"z\":12,\"x\":1,\"y\":2}"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        ByteBuffer buffer = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
        assertArrayEquals(new byte[]{'G', 'J', 'N', 'B'}, new byte[]{body[0], body[1], body[2], body[3]});
        assertEquals(1, buffer.getShort(4));
        assertEquals(64, buffer.getShort(6));
        assertEquals(1, buffer.getInt(8));
        assertEquals(1, buffer.getInt(12));
        assertEquals(100.0, buffer.getDouble(16));
        assertEquals(200.0, buffer.getDouble(24));
        assertEquals(15.5f, buffer.getFloat(buffer.getInt(44)));
        assertEquals(10.25f, buffer.getFloat(buffer.getInt(44) + Float.BYTES));
        assertEquals(7.0f, buffer.getFloat(buffer.getInt(48)));
        assertEquals(42.5f, buffer.getFloat(buffer.getInt(52)));
        assertEquals(2.0f, buffer.getFloat(buffer.getInt(56)));
    }
}
