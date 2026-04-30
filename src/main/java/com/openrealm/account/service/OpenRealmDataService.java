package com.openrealm.account.service;

import java.util.Map;

public interface OpenRealmDataService {
    public <T> T executeDelete(String path, Class<T> responseClass) throws Exception;
    public <T> T executePost(String path, Object object, Class<T> responseClass) throws Exception;
    public <T> T executePut(String path, Object object, Class<T> responseClass) throws Exception;
    public String executeGet(String path, Map<String, String> queryParams) throws Exception;
    public <T> T executeGet(String path, Map<String, String> queryParams, Class<T> responseClass) throws Exception;
    public <T> T executeGetWithToken(String path, String token, Class<T> responseClass) throws Exception;
}
