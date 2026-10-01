package com.github.shikejing.reportenginejava.service;

import com.github.shikejing.reportenginejava.exception.NotFoundException;
import com.github.shikejing.reportenginejava.exception.ValidationException;
import com.github.shikejing.reportenginejava.model.CallerPrincipal;
import com.github.shikejing.reportenginejava.model.ReportDefinition;
import com.github.shikejing.reportenginejava.model.StoredDefinition;
import com.github.shikejing.reportenginejava.model.ValidationIssue;
import com.github.shikejing.reportenginejava.repository.FileSystemDefinitionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class DefinitionService {

    private final DefinitionValidator validator;
    private final FileSystemDefinitionRepository repository;
    private final AuthService authService;
    private final Clock clock;

    public DefinitionService(DefinitionValidator validator,
                             FileSystemDefinitionRepository repository,
                             AuthService authService,
                             Clock clock) {
        this.validator = validator;
        this.repository = repository;
        this.authService = authService;
        this.clock = clock;
    }

    public StoredDefinition createOrUpdate(CallerPrincipal caller,
                                           String reportKey,
                                           ReportDefinition definition,
                                           Integer requestedEngineVersion) {
        authService.requireAdmin(caller, caller.scope());
        StoredDefinition stored = repository.saveNext(caller.scope(), reportKey, (version, latest) -> {
            int engineVersion = latest.isEmpty()
                    ? (requestedEngineVersion == null ? 2 : requestedEngineVersion)
                    : (requestedEngineVersion == null ? latest.get().engineVersion() : requestedEngineVersion);
            List<ValidationIssue> issues = new java.util.ArrayList<>(validator.validate(definition, engineVersion));
            if (!reportKey.equals(definition.reportKey())) {
                issues.add(new ValidationIssue("$.reportKey", "must match URL report key"));
            }
            if (requestedEngineVersion != null && latest.isPresent()
                    && requestedEngineVersion < latest.get().engineVersion()) {
                issues.add(new ValidationIssue("$.engineVersion",
                        "cannot downgrade from engine version " + latest.get().engineVersion()));
            }
            if (!issues.isEmpty()) {
                throw new ValidationException(issues);
            }
            return new StoredDefinition(
                    caller.scope(),
                    reportKey,
                    version,
                    engineVersion,
                    Instant.now(clock),
                    definition
            );
        });
        return stored;

    }

    public StoredDefinition get(CallerPrincipal caller, String reportKey, Integer version) {
        authService.requireReader(caller, caller.scope());
        StoredDefinition stored = version == null
                ? repository.findLatest(caller.scope(), reportKey).orElse(null)
                : repository.find(caller.scope(), reportKey, version).orElse(null);
        if (stored == null || !stored.scope().equals(caller.scope())) {
            throw new NotFoundException("report definition not found");
        }
        return stored;
    }

    public List<StoredDefinition> list(CallerPrincipal caller, String reportKey) {
        authService.requireReader(caller, caller.scope());
        return repository.findAll(caller.scope(), reportKey);
    }

    public void delete(CallerPrincipal caller, String reportKey) {
        authService.requireAdmin(caller, caller.scope());
        if (!repository.delete(caller.scope(), reportKey)) {
            throw new NotFoundException("report definition not found");
        }
    }
}
