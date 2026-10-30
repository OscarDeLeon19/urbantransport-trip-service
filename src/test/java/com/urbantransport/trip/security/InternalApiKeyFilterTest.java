package com.urbantransport.trip.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InternalApiKeyFilterTest {

    private static final String API_KEY = "clave-interna";
    private static final String INTERNAL_URI = "/internal/trips/active";

    private final InternalApiKeyFilter filter = new InternalApiKeyFilter(API_KEY);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void correctKeyOnInternalPath_authenticatesAsInternalService() throws Exception {
        MockFilterChain chain = runFilter(filter, INTERNAL_URI, API_KEY);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(authentication);
        assertTrue(authentication.isAuthenticated());
        assertEquals("internal-service", authentication.getPrincipal());
        assertEquals(1, authentication.getAuthorities().size());
        assertEquals("ROLE_INTERNAL_SERVICE",
                authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).findFirst().orElseThrow());
        assertNotNull(chain.getRequest());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"otra-clave", "CLAVE-INTERNA", "clave-interna "})
    void wrongOrMissingKey_continuesWithoutAuthentication(String providedKey) throws Exception {
        MockFilterChain chain = runFilter(filter, INTERNAL_URI, providedKey);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertNotNull(chain.getRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/trips", "/trips/internal/1", "/internalx/trips"})
    void correctKeyOutsideInternalPath_isNotAuthenticatedByThisFilter(String uri) throws Exception {
        MockFilterChain chain = runFilter(filter, uri, API_KEY);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertNotNull(chain.getRequest());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void filterWithoutConfiguredKey_neverAuthenticates(String configuredKey) throws Exception {
        InternalApiKeyFilter filterWithoutKey = new InternalApiKeyFilter(configuredKey);

        MockFilterChain chain = runFilter(filterWithoutKey, INTERNAL_URI, configuredKey == null ? "" : configuredKey);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertNotNull(chain.getRequest());
    }

    private static MockFilterChain runFilter(InternalApiKeyFilter filter, String uri, String providedKey)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        if (providedKey != null) {
            request.addHeader(InternalApiKeyFilter.HEADER, providedKey);
        }
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return chain;
    }
}
