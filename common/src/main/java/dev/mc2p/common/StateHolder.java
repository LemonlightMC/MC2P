
package dev.mc2p.common;

import java.nio.file.Path;

import org.slf4j.Logger;

import dev.mc2p.common.activity.ActivityLogger;
import dev.mc2p.common.activity.ClientActivityTracker;
import dev.mc2p.common.config.BaseConfig;
import dev.mc2p.common.tokens.TokenManager;

public interface StateHolder<T extends BaseConfig> {
  public ActivityLogger audit();

  public ClientActivityTracker activity();

  public TokenManager tokens();

  public T config();

  public Path dataDirectory();

  public void teardown();

  public void init();

  public Logger logger();

}