package us.beiyue.beilinentrycontrol.login;

import net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import us.beiyue.beilinentrycontrol.BeilinEntryControl;
import us.beiyue.beilinentrycontrol.gate.EntryGate;
import us.beiyue.beilinentrycontrol.http.BeilinApiClient;
import us.beiyue.beilinentrycontrol.http.BeilinApiClient.JoinResult;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

/**
 * INIT / QUERY_START: disconnect when WS down or API denies.
 * Uses Connection.disconnect(Component) — handler.disconnect() is not always public at runtime.
 */
public final class LoginHandler {
	private static BeilinApiClient apiClient;

	public static void setApiClient(BeilinApiClient client) {
		apiClient = client;
	}

	public static void register() {
		ServerLoginConnectionEvents.INIT.register((handler, server) -> {
			if (!server.isDedicatedServer()) return;
			if (!EntryGate.isAcceptingPlayers()) {
				forceDisconnect(handler, EntryGate.SYNC_MESSAGE);
			}
		});

		ServerLoginConnectionEvents.QUERY_START.register((handler, server, sender, synchronizer) -> {
			if (!server.isDedicatedServer()) return;
			if (!EntryGate.isAcceptingPlayers()) {
				forceDisconnect(handler, EntryGate.SYNC_MESSAGE);
				return;
			}
			if (apiClient == null) {
				forceDisconnect(handler, "Beilin API 未初始化");
				return;
			}
			String username = getUsername(handler);
			if (username == null || username.isEmpty()) {
				forceDisconnect(handler, "无法取得玩家名");
				return;
			}

			CompletableFuture<Void> done = new CompletableFuture<>();

			apiClient.playerJoinAsync(username)
				.thenAccept(result -> {
					server.execute(() -> {
						try {
							if (!result.ok) {
								String reason = result.reason != null ? result.reason : "无权进入服务器";
								forceDisconnect(handler, reason);
							}
						} finally {
							done.complete(null);
						}
					});
				})
				.exceptionally(ex -> {
					server.execute(() -> {
						try {
							forceDisconnect(handler, "网络异常");
						} finally {
							done.complete(null);
						}
					});
					return null;
				});

			synchronizer.waitFor(done);
		});
	}

	private static void serverExecuteCloseChannel(Object handler) {
		// Last resort: close netty channel so connection dies even if disconnect() unavailable
		Object conn = findConnection(handler);
		if (conn == null) return;
		try {
			for (Field f : conn.getClass().getDeclaredFields()) {
				if (f.getName().equals("channel") || f.getType().getSimpleName().contains("Channel")) {
					f.setAccessible(true);
					Object ch = f.get(conn);
					if (ch != null) {
						Method close = ch.getClass().getMethod("close");
						close.invoke(ch);
						return;
					}
				}
			}
		} catch (Exception ignored) {
		}
	}

	/** @return true if connection was closed / disconnect invoked */
	private static boolean forceDisconnect(Object handler, String message) {
		Component text = Component.literal(message);

		// 1) Directly use ServerLoginPacketListenerImpl API (Mojang mappings; obf-safe)
		if (handler instanceof ServerLoginPacketListenerImpl login) {
			login.disconnect(text);
			return true;
		}

		// 2) Underlying net.minecraft.network.Connection
		Connection conn = findConnection(handler);
		if (conn != null) {
			conn.disconnect(text);
			return true;
		}

		// 3) Fallback: best-effort close of Netty channel
		BeilinEntryControl.LOGGER.error("forceDisconnect: no working disconnect for {}", handler.getClass().getName());
		serverExecuteCloseChannel(handler);
		return false;
	}

	private static Connection findConnection(Object handler) {
		Class<?> c = handler.getClass();
		while (c != null) {
			for (Field f : c.getDeclaredFields()) {
				if (!Connection.class.isAssignableFrom(f.getType())) continue;
				try {
					f.setAccessible(true);
					Object v = f.get(handler);
					if (v instanceof Connection conn) {
						return conn;
					}
				} catch (Exception ignored) {
				}
			}
			c = c.getSuperclass();
		}
		return null;
	}

	private static String getUsername(Object handler) {
		// Try to read username from any GameProfile field on the login handler.
		Class<?> c = handler.getClass();
		while (c != null) {
			for (Field f : c.getDeclaredFields()) {
				if (!"com.mojang.authlib.GameProfile".equals(f.getType().getName())) continue;
				try {
					f.setAccessible(true);
					Object v = f.get(handler);
					if (v != null) {
						try {
							Method m = v.getClass().getMethod("getName");
							Object name = m.invoke(v);
							if (name instanceof String s && !s.isEmpty()) {
								return s;
							}
						} catch (Exception ignored) {
						}
					}
				} catch (Exception e) {
					BeilinEntryControl.LOGGER.debug("Profile field get failed", e);
				}
			}
			c = c.getSuperclass();
		}
		return null;
	}
}
