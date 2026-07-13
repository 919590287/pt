package com.jts.gjcxfzksh.api.controller;

import com.jts.gjcxfzksh.api.model.params.DatasourceParam;
import com.jts.gjcxfzksh.api.service.PTDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PTDataControllerCacheContractTest {

    private PTDataService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(PTDataService.class);
        PTDataController controller = new PTDataController();
        ReflectionTestUtils.setField(controller, "service", service);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void getTrajectoryChunkBinaryReturnsFileResourceWithStrongCachingHeaders() throws Exception {
        Path chunk = Files.createTempFile("trajectory-chunk", ".bin");
        byte[] payload = new byte[]{'G', 'J', 'T', 'B', 1, 2, 3, 4};
        Files.write(chunk, payload);
        when(service.trajectoryChunkTag(any(DatasourceParam.class), eq(300))).thenReturn("\"trajectory-v8-model-300\"");
        when(service.trajectoryChunkBinaryPath(any(DatasourceParam.class), eq(300))).thenReturn(chunk);

        byte[] response = mockMvc.perform(get("/pt/data/trajectory/chunk.bin")
                        .param("datasource", "area/public/model")
                        .param("start", "300"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"trajectory-v8-model-300\""))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, payload.length))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=31536000, private, immutable"))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        assertArrayEquals(payload, response);
        ArgumentCaptor<DatasourceParam> param = ArgumentCaptor.forClass(DatasourceParam.class);
        verify(service).trajectoryChunkTag(param.capture(), eq(300));
        assertEquals("area/public/model", param.getValue().getDatasource());
        verify(service, never()).trajectoryChunkBinary(any(DatasourceParam.class), eq(300));
        Files.deleteIfExists(chunk);
    }

    @Test
    void getTrajectoryChunkBinaryReturnsNotModifiedWhenIfNoneMatchMatches() throws Exception {
        when(service.trajectoryChunkTag(any(DatasourceParam.class), eq(0))).thenReturn("\"trajectory-v8-model-0\"");

        mockMvc.perform(get("/pt/data/trajectory/chunk.bin")
                        .param("datasource", "area/public/model")
                        .header(HttpHeaders.IF_NONE_MATCH, "\"other\", \"trajectory-v8-model-0\""))
                .andExpect(status().isNotModified())
                .andExpect(header().string(HttpHeaders.ETAG, "\"trajectory-v8-model-0\""))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=31536000, private, immutable"));

        verify(service, never()).trajectoryChunkBinaryPath(any(DatasourceParam.class), eq(0));
        verify(service, never()).trajectoryChunkBinary(any(DatasourceParam.class), eq(0));
    }

    @Test
    void getTrajectoryChunkBinaryReturnsAcceptedAndNoStoreWhenChunkIsStillBuilding() throws Exception {
        when(service.trajectoryChunkTag(any(DatasourceParam.class), eq(600))).thenReturn("\"trajectory-v8-model-600\"");
        when(service.trajectoryChunkBinaryPath(any(DatasourceParam.class), eq(600))).thenReturn(null);
        when(service.trajectoryChunkBinary(any(DatasourceParam.class), eq(600))).thenReturn(null);

        mockMvc.perform(get("/pt/data/trajectory/chunk.bin")
                        .param("datasource", "area/public/model")
                        .param("start", "600"))
                .andExpect(status().isAccepted())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    }

    @Test
    void postTrajectoryChunkBinaryKeepsAcceptedContractForColdCache() throws Exception {
        when(service.trajectoryChunkBinary(any(DatasourceParam.class), eq(0))).thenReturn(null);

        mockMvc.perform(post("/pt/data/trajectory/chunk.bin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"datasource\":\"area/public/model\"}"))
                .andExpect(status().isAccepted())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE));
    }

    @Test
    void getTrajectoryFrameBinaryReturnsNoStoreViewportSnapshot() throws Exception {
        byte[] payload = new byte[]{'G', 'J', 'T', 'B', 1, 0, 64, 0};
        when(service.trajectoryFrameBinary(
                any(DatasourceParam.class),
                eq(28_800),
                eq(300),
                eq("public"),
                eq(1000.0),
                eq(2000.0),
                eq(3000.0),
                eq(4000.0)
        )).thenReturn(payload);

        byte[] response = mockMvc.perform(get("/pt/data/trajectory/frame.bin")
                        .param("datasource", "area/public/model")
                        .param("time", "28800")
                        .param("bucketSeconds", "300")
                        .param("visibilityMode", "public")
                        .param("minX", "1000")
                        .param("minY", "2000")
                        .param("maxX", "3000")
                        .param("maxY", "4000"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, payload.length))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        assertArrayEquals(payload, response);
    }
}
