package com.zeti.api.presentation;

import com.zeti.api.address.application.AddressService;
import com.zeti.api.address.presentation.AddressController;
import com.zeti.api.order.application.OrderService;
import com.zeti.api.order.presentation.OrderController;
import com.zeti.api.user.application.UserService;
import com.zeti.api.user.presentation.UserController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SelfScopedEndpointTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new AddressController(mock(AddressService.class)),
                new OrderController(mock(OrderService.class)),
                new UserController(mock(UserService.class)))
                .build();
    }

    @Test
    void userIdBasedCollectionReadsAreNotExposed() throws Exception {
        mockMvc.perform(get("/addresses/140000010"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(get("/orders/140000010"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/users/140000010"))
                .andExpect(status().isNotFound());
    }

    @Test
    void userProfileUpdateIsOnlyExposedAsSelfResource() throws Exception {
        mockMvc.perform(put("/users/140000010")
                        .contentType("application/json")
                        .content("{\"name\":\"수정\"}"))
                .andExpect(status().isNotFound());
    }
}
