package com.taskflow.admin.access.service;

import com.taskflow.admin.access.dto.AccessResponses.*;
import org.springframework.security.oauth2.jwt.Jwt;

public interface IAccessService {
    Configuration selectConfiguration();
    Current selectCurrent(Jwt jwt);
}
