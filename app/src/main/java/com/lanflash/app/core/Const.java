package com.lanflash.app.core;

/**
 * 全局常量与超时参数。
 */
public final class Const {

    private Const() {}

    /** 协议版本。发现广播与 HELLO 都携带。 */
    public static final int PROTO_VERSION = 1;

    /** NSD 服务类型：固定私有类型，避免与其它 App 撞车。 */
    public static final String SERVICE_TYPE = "_lanflash._tcp";

    /** 默认 TCP 服务端口。被占用时向后顺延。 */
    public static final int DEFAULT_TCP_PORT = 45960;
    /** 端口顺延的最大尝试次数。 */
    public static final int PORT_SCAN_RANGE = 40;

    /** 组播兜底：固定组播组与端口。 */
    public static final String MULTICAST_GROUP = "239.255.42.88";
    public static final int MULTICAST_PORT = 45961;

    /** NSD 无邻居后启用组播的等待时间。 */
    public static final long DISCOVERY_FALLBACK_MS = 5000L;

    /** 组播广播周期与随机抖动。 */
    public static final long MULTICAST_INTERVAL_MS = 5000L;
    public static final int MULTICAST_JITTER_MS = 1500;

    /** 邻居过期时间：30 秒。 */
    public static final long PEER_EXPIRE_MS = 30000L;

    /** 心跳/状态刷新周期。 */
    public static final long HEARTBEAT_MS = 10000L;

    /** 连接与读写超时。 */
    public static final int SOCKET_TIMEOUT_MS = 15000;

    /** 文件分块：128KB。 */
    public static final int CHUNK_SIZE = 128 * 1024;

    /** 单帧 payload 上限。 */
    public static final int MAX_FRAME_BYTES = 2 * 1024 * 1024;

    /** 信任确认等待上限。 */
    public static final long TRUST_WAIT_MS = 60000L;

    /** 文本消息最大长度（字符）。 */
    public static final int MAX_TEXT_CHARS = 4000;

    /** 每个对端的本地聊天记录上限。 */
    public static final int MAX_MESSAGES_PER_PEER = 500;

    /** 前台服务通知 ID。 */
    public static final int NOTIF_ID_SERVICE = 1003;
    public static final int NOTIF_ID_TRANSFER = 1002;
}
