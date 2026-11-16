package com.minismp.bridge.mcpe;

/**
 * MCPE 1.1.3 / 1.1.5 / Minecraft 3DS 1.10 (Protocol 113) constants from Nukkit-Retro.
 */
public final class McpeProtocol {

    private McpeProtocol() {
    }

    public static final int PROTOCOL_VERSION = 113;
    public static final String MINECRAFT_VERSION = "1.1.3";
    public static final int RAKNET_PROTOCOL_VERSION = 6;
    public static final int[] SUPPORTED_RAKNET_PROTOCOLS = new int[]{6, 7, 8};

    public static final byte[] RAKNET_MAGIC = new byte[]{
            0x00, (byte) 0xff, (byte) 0xff, 0x00,
            (byte) 0xfe, (byte) 0xfe, (byte) 0xfe, (byte) 0xfe,
            (byte) 0xfd, (byte) 0xfd, (byte) 0xfd, (byte) 0xfd,
            0x12, 0x34, 0x56, 0x78
    };

    // MCPE 1.1.3 / 1.1.5 Packet IDs (inside BatchPacket)
    public static final int LOGIN_PACKET = 0x01;
    public static final int PLAY_STATUS_PACKET = 0x02;
    public static final int SERVER_TO_CLIENT_HANDSHAKE_PACKET = 0x03;
    public static final int CLIENT_TO_SERVER_HANDSHAKE_PACKET = 0x04;
    public static final int DISCONNECT_PACKET = 0x05;
    public static final int RESOURCE_PACKS_INFO_PACKET = 0x06;
    public static final int RESOURCE_PACK_STACK_PACKET = 0x07;
    public static final int RESOURCE_PACK_CLIENT_RESPONSE_PACKET = 0x08;
    public static final int TEXT_PACKET = 0x09;
    public static final int SET_TIME_PACKET = 0x0a;
    public static final int START_GAME_PACKET = 0x0b;
    public static final int ADD_PLAYER_PACKET = 0x0c;
    public static final int ADD_ENTITY_PACKET = 0x0d;
    public static final int REMOVE_ENTITY_PACKET = 0x0e;
    public static final int ADD_ITEM_ENTITY_PACKET = 0x0f;
    public static final int ADD_HANGING_ENTITY_PACKET = 0x10;
    public static final int TAKE_ITEM_ENTITY_PACKET = 0x11;
    public static final int MOVE_ENTITY_PACKET = 0x12;
    public static final int MOVE_PLAYER_PACKET = 0x13;
    public static final int RIDER_JUMP_PACKET = 0x14;
    public static final int REMOVE_BLOCK_PACKET = 0x15;
    public static final int UPDATE_BLOCK_PACKET = 0x16;
    public static final int ADD_PAINTING_PACKET = 0x17;
    public static final int EXPLODE_PACKET = 0x18;
    public static final int LEVEL_SOUND_EVENT_PACKET = 0x19;
    public static final int LEVEL_EVENT_PACKET = 0x1a;
    public static final int BLOCK_EVENT_PACKET = 0x1b;
    public static final int ENTITY_EVENT_PACKET = 0x1c;
    public static final int MOB_EFFECT_PACKET = 0x1d;
    public static final int UPDATE_ATTRIBUTES_PACKET = 0x1e;
    public static final int MOB_EQUIPMENT_PACKET = 0x1f;
    public static final int MOB_ARMOR_EQUIPMENT_PACKET = 0x20;
    public static final int INTERACT_PACKET = 0x21;
    public static final int BLOCK_PICK_REQUEST_PACKET = 0x22;
    public static final int USE_ITEM_PACKET = 0x23;
    public static final int PLAYER_ACTION_PACKET = 0x24;
    public static final int ENTITY_FALL_PACKET = 0x25;
    public static final int HURT_ARMOR_PACKET = 0x26;
    public static final int SET_ENTITY_DATA_PACKET = 0x27;
    public static final int SET_ENTITY_MOTION_PACKET = 0x28;
    public static final int SET_ENTITY_LINK_PACKET = 0x29;
    public static final int SET_HEALTH_PACKET = 0x2a;
    public static final int SET_SPAWN_POSITION_PACKET = 0x2b;
    public static final int ANIMATE_PACKET = 0x2c;
    public static final int RESPAWN_PACKET = 0x2d;
    public static final int DROP_ITEM_PACKET = 0x2e;
    public static final int INVENTORY_ACTION_PACKET = 0x2f;
    public static final int CONTAINER_OPEN_PACKET = 0x30;
    public static final int CONTAINER_CLOSE_PACKET = 0x31;
    public static final int CONTAINER_SET_SLOT_PACKET = 0x32;
    public static final int CONTAINER_SET_DATA_PACKET = 0x33;
    public static final int CONTAINER_SET_CONTENT_PACKET = 0x34;
    public static final int ADVENTURE_SETTINGS_PACKET = 0x37;
    public static final int FULL_CHUNK_DATA_PACKET = 0x3a;
    public static final int SET_COMMANDS_ENABLED_PACKET = 0x3b;
    public static final int SET_DIFFICULTY_PACKET = 0x3c;
    public static final int CHANGE_DIMENSION_PACKET = 0x3d;
    public static final int SET_PLAYER_GAME_TYPE_PACKET = 0x3e;
    public static final int PLAYER_LIST_PACKET = 0x3f;
    public static final int REQUEST_CHUNK_RADIUS_PACKET = 0x45;
    public static final int CHUNK_RADIUS_UPDATED_PACKET = 0x46;

    // Outer RakNet game packet wrapper byte used by MCPE 0.15 - 1.1.x (0xFE)
    public static final int RAKNET_GAME_PACKET_WRAPPER = 0xfe;

    // PlayStatus values
    public static final int PLAY_STATUS_LOGIN_SUCCESS = 0;
    public static final int PLAY_STATUS_LOGIN_FAILED_CLIENT = 1;
    public static final int PLAY_STATUS_LOGIN_FAILED_SERVER = 2;
    public static final int PLAY_STATUS_PLAYER_SPAWN = 3;

    // ResourcePackClientResponse values
    public static final int RESOURCE_PACK_STATUS_NONE = 0;
    public static final int RESOURCE_PACK_STATUS_REFUSED = 1;
    public static final int RESOURCE_PACK_STATUS_SEND_PACKS = 2;
    public static final int RESOURCE_PACK_STATUS_HAVE_ALL_PACKS = 3;
    public static final int RESOURCE_PACK_STATUS_COMPLETED = 4;

    // TextPacket types
    public static final byte TEXT_RAW = 0;
    public static final byte TEXT_CHAT = 1;
    public static final byte TEXT_TRANSLATION = 2;
    public static final byte TEXT_POPUP = 3;
    public static final byte TEXT_TIP = 4;
    public static final byte TEXT_SYSTEM = 5;
}
