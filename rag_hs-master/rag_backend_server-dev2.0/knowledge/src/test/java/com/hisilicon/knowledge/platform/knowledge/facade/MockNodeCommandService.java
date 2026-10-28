package com.hisilicon.knowledge.platform.knowledge.facade;

import com.hisilicon.knowledge.platform.knowledge.command.CreateNodeCommand;
import com.hisilicon.knowledge.platform.knowledge.command.DeleteNodeCommand;
import com.hisilicon.knowledge.platform.knowledge.command.NodeCommandService;
import com.hisilicon.knowledge.platform.knowledge.command.RenameNodeCommand;
import com.hisilicon.knowledge.platform.knowledge.query.dto.NodeCreatedResult;
import io.smallrye.mutiny.Uni;

/**
 * Mock — 替换 {@link NodeCommandService}，记录最近一次命令供透传断言
 */
public class MockNodeCommandService extends NodeCommandService {

    private static volatile CreateNodeCommand lastCreate;
    private static volatile RenameNodeCommand lastRename;
    private static volatile DeleteNodeCommand lastDelete;
    private static volatile NodeCreatedResult presetResult;

    public MockNodeCommandService() {
        super(null, null, null, null, null);
    }

    public static void setPresetResult(NodeCreatedResult result) {
        presetResult = result;
    }

    public static CreateNodeCommand getLastCreate() {
        return lastCreate;
    }

    public static RenameNodeCommand getLastRename() {
        return lastRename;
    }

    public static DeleteNodeCommand getLastDelete() {
        return lastDelete;
    }

    public static void reset() {
        lastCreate = null;
        lastRename = null;
        lastDelete = null;
        presetResult = null;
    }

    @Override
    public Uni<NodeCreatedResult> create(String operatorId, CreateNodeCommand command, String locale) {
        lastCreate = command;
        return Uni.createFrom().item(presetResult != null
                ? presetResult
                : new NodeCreatedResult("node-1", "CATEGORY", command.name()));
    }

    @Override
    public Uni<Void> rename(String operatorId, RenameNodeCommand command, String locale) {
        lastRename = command;
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Void> delete(String operatorId, DeleteNodeCommand command, String locale) {
        lastDelete = command;
        return Uni.createFrom().voidItem();
    }
}
