package com.stream2song.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix="music")
public record MusicProperties(boolean enabled, String provider, String replicateApiToken) {
    public MusicProperties { provider=provider==null||provider.isBlank()?"replicate":provider; }
    public boolean isConfigured(){return enabled&&"replicate".equals(provider)&&replicateApiToken!=null&&!replicateApiToken.isBlank();}
    @Override public String toString(){return "MusicProperties[enabled="+enabled+", provider="+provider+", replicateApiToken=redacted]";}
}
