package cn.qinzhikeji.easybackup.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelSummary;

import java.nio.file.Path;

/**
 * 一个存档的引用。{@code current} 表示它是当前正在游玩的单人世界。
 */
public record WorldRef(String levelId,
					   String levelName,
					   Path worldPath,
					   boolean current,
					   boolean locked,
					   MinecraftServer server) {

	public static WorldRef current(Minecraft minecraft) {
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (server == null) {
			return null;
		}
		// LevelResource.ROOT 的 id 是 "."，getWorldPath 返回的世界目录末尾会带 "/."，
		// 不 normalize 的话 getFileName() 得到的是 "." 而不是世界文件夹名。
		Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
		String levelId = root.getFileName() == null ? "" : root.getFileName().toString();
		String levelName = server.getWorldData().getLevelName();
		return new WorldRef(levelId, levelName, root, true, false, server);
	}

	public static WorldRef fromSummary(Minecraft minecraft, LevelSummary summary) {
		Path path = minecraft.getLevelSource().getLevelPath(summary.getLevelId())
				.toAbsolutePath().normalize();
		return new WorldRef(summary.getLevelId(), summary.getLevelName(), path,
				false, summary.isLocked(), null);
	}

	public WorldRef detached() {
		return new WorldRef(levelId, levelName, worldPath, false, locked, null);
	}
}
