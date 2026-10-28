package com.taskflow.admin.access.service.impl;

import com.taskflow.admin.access.config.AccessProperties;
import com.taskflow.admin.access.dto.AccessResponses.*;
import com.taskflow.admin.access.model.AccessPolicy;
import com.taskflow.admin.access.service.IAccessService;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

@Service
public class AccessServiceImpl implements IAccessService {
    private final AccessProperties properties;
    public AccessServiceImpl(AccessProperties properties) { this.properties = properties; }
    @Override public Configuration selectConfiguration() {
        return new Configuration(properties.issuer(), properties.clientId());
    }
    @Override public Current selectCurrent(Jwt jwt) {
        var roles = AccessPolicy.roles(jwt, properties.audience());
        var username = jwt.getClaimAsString("preferred_username");
        return new Current(jwt.getSubject(), username == null ? jwt.getSubject() : username,
                roles, AccessPolicy.permissions(roles));
    }
}
