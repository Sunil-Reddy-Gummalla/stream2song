package com.stream2song.backend.controller;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.stream2song.backend.model.SongJob;
import com.stream2song.backend.service.SongJobService;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SongControllerTest {
    private MockMvc mockMvc;
    private SongJobService songJobService;

    @BeforeEach
    void setUp() {
        songJobService = new SongJobService();
        mockMvc = MockMvcBuilders.standaloneSetup(new SongController(songJobService))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void createsSongJob() throws Exception {
        mockMvc.perform(post("/api/songs")
                        .contentType(APPLICATION_JSON)
                        .content("{\"channelName\":\"streamer_one\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/songs/")))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.channelName", is("streamer_one")))
                .andExpect(jsonPath("$.status", is("PENDING")))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }

    @Test
    void listsJobsCreatedDuringTheCurrentRun() throws Exception {
        songJobService.create("first_streamer");
        songJobService.create("second_streamer");

        mockMvc.perform(get("/api/songs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].channelName", containsInAnyOrder("first_streamer", "second_streamer")));
    }

    @Test
    void retrievesJobById() throws Exception {
        SongJob job = songJobService.create("streamer_one");

        mockMvc.perform(get("/api/songs/{id}", job.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(job.id().toString())))
                .andExpect(jsonPath("$.channelName", is("streamer_one")))
                .andExpect(jsonPath("$.status", is("PENDING")));
    }

    @Test
    void rejectsInvalidChannelNames() throws Exception {
        mockMvc.perform(post("/api/songs")
                        .contentType(APPLICATION_JSON)
                        .content("{\"channelName\":\"bad name\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").isNotEmpty());
    }

    @Test
    void rejectsMissingChannelName() throws Exception {
        mockMvc.perform(post("/api/songs")
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").isNotEmpty());
    }

    @Test
    void returnsNotFoundForUnknownId() throws Exception {
        mockMvc.perform(get("/api/songs/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").isNotEmpty());
    }
}
