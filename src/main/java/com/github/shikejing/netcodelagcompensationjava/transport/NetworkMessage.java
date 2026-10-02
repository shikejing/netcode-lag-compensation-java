package com.github.shikejing.netcodelagcompensationjava.transport;

import com.github.shikejing.netcodelagcompensationjava.input.PlayerInput;
import com.github.shikejing.netcodelagcompensationjava.snapshot.WorldSnapshot;

public sealed interface NetworkMessage permits NetworkMessage.InputMessage, NetworkMessage.SnapshotMessage {
    record InputMessage(String playerId, PlayerInput input) implements NetworkMessage { }
    record SnapshotMessage(WorldSnapshot snapshot) implements NetworkMessage { }
}
