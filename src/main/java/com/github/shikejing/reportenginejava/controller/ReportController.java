package com.github.shikejing.reportenginejava.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.shikejing.reportenginejava.model.CallerPrincipal;
import com.github.shikejing.reportenginejava.model.RenderJob;
import com.github.shikejing.reportenginejava.model.ReportDefinition;
import com.github.shikejing.reportenginejava.model.StoredDefinition;
import com.github.shikejing.reportenginejava.dto.RenderBatch;
import com.github.shikejing.reportenginejava.service.AuthService;
import com.github.shikejing.reportenginejava.service.DefinitionService;
import com.github.shikejing.reportenginejava.service.RenderJobService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final AuthService authService;
    private final DefinitionService definitionService;
    private final RenderJobService renderJobService;
    private final ObjectMapper objectMapper;

    public ReportController(AuthService authService,
                            DefinitionService definitionService,
                            RenderJobService renderJobService,
                            ObjectMapper objectMapper) {
        this.authService = authService;
        this.definitionService = definitionService;
        this.renderJobService = renderJobService;
        this.objectMapper = objectMapper;
    }

    @PutMapping(path = "/{reportKey}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public StoredDefinition putDefinition(@PathVariable String reportKey,
                                          @RequestBody ReportDefinition definition,
                                          @RequestHeader(name = "X-Engine-Version", required = false)
                                          Integer engineVersion,
                                          HttpServletRequest request) {
        CallerPrincipal caller = authService.authenticate(request);
        return definitionService.createOrUpdate(caller, reportKey, definition, engineVersion);
    }

    @GetMapping(path = "/{reportKey}/versions", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<StoredDefinition> versions(@PathVariable String reportKey,
                                           HttpServletRequest request) {
        CallerPrincipal caller = authService.authenticate(request);
        return definitionService.list(caller, reportKey);
    }

    @GetMapping(path = "/{reportKey}", produces = MediaType.APPLICATION_JSON_VALUE)
    public StoredDefinition getDefinition(@PathVariable String reportKey,
                                          @RequestParam(name = "version", required = false) Integer version,
                                          HttpServletRequest request) {
        CallerPrincipal caller = authService.authenticate(request);
        return definitionService.get(caller, reportKey, version);
    }

    @DeleteMapping(path = "/{reportKey}")
    public Map<String, Object> deleteDefinition(@PathVariable String reportKey,
                                                HttpServletRequest request) {
        CallerPrincipal caller = authService.authenticate(request);
        definitionService.delete(caller, reportKey);
        return Map.of("deleted", true);
    }

    @PostMapping(path = "/{reportKey}/renders", consumes = "application/x-ndjson")
    public RenderJob render(@PathVariable String reportKey,
                            @RequestParam(name = "version", required = false) Integer version,
                            @RequestParam(name = "p", required = false) Map<String, String> rawParameters,
                            HttpServletRequest request) {
        CallerPrincipal caller = authService.authenticate(request);
        try {
            return renderJobService.start(caller, reportKey, version, request.getInputStream(),
                    readParameters(request, rawParameters));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @GetMapping(path = "/renders/{jobId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public RenderJob status(@PathVariable String jobId, HttpServletRequest request) {
        CallerPrincipal caller = authService.authenticate(request);
        return renderJobService.status(caller, jobId);
    }

    @GetMapping(path = "/renders/{jobId}/rows", produces = MediaType.APPLICATION_JSON_VALUE)
    public RenderBatch rows(@PathVariable String jobId,
                            @RequestParam(name = "offset", defaultValue = "0") long offset,
                            @RequestParam(name = "limit", required = false) Integer limit,
                            HttpServletRequest request) {
        CallerPrincipal caller = authService.authenticate(request);
        return renderJobService.batch(caller, jobId, offset, limit);
    }

    @PostMapping(path = "/renders/{jobId}/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
    public RenderJob cancel(@PathVariable String jobId, HttpServletRequest request) {
        CallerPrincipal caller = authService.authenticate(request);
        return renderJobService.cancel(caller, jobId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readParameters(HttpServletRequest request, Map<String, String> raw)
            throws IOException {
        Map<String, Object> result = new java.util.HashMap<>();
        if (raw != null) {
            raw.forEach(result::put);
        }
        String json = request.getHeader("X-Parameters-JSON");
        if (json != null && !json.isBlank()) {
            Map<String, Object> parsed = objectMapper.readValue(json,
                    new TypeReference<Map<String, Object>>() {});
            result.putAll(parsed);
        }
        return Map.copyOf(result);
    }
}
