package com.ae2vm.trace;

import com.ae2vm.config.AE2VMConfig;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.PlayerNotFoundException;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.event.ClickEvent;
import net.minecraft.util.text.event.HoverEvent;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.Style;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import com.ae2vm.Log;
import java.util.Collections;

/**
 * /ae2vm trace ... — the Evidence entry points. The
 * command is a SERVER command: vanilla clients can run it from chat, which
 * is exactly how players without the mod get their traces .
 * Visibility: players see only their own traces; ops see everything.
 * Upload is separately gated (public-by-URL leak point).
 */
public final class TraceCommand extends CommandBase {

    private static final int PAGE_SIZE = 8;

    @Override
    public String getName() {
        return "ae2vm";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/ae2vm trace record|list|show|upload|download";
    }

    /** Internal per-subcommand checks; do not gate the whole tree behind op. */
    @Override
    public boolean checkPermission(MinecraftServer server, ICommandSender sender) {
        return true;
    }

    private static boolean isOp(ICommandSender sender) {
        return sender.canUseCommand(2, "ae2vm");
    }

    private static EntityPlayer asPlayer(ICommandSender sender) throws PlayerNotFoundException {
        return getCommandSenderAsPlayer(sender);
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args)
            throws CommandException {
        if (args.length < 2 || !"trace".equals(args[0])) {
            throw new WrongUsageException(TraceLang.format("aevm.trace.usage"));
        }
        String sub = args[1];
        switch (sub) {
            case "record":
                record(sender, args);
                return;
            case "list":
                list(sender, args);
                return;
            case "show":
                requireId(sender, args);
                show(sender, args[2]);
                return;
            case "upload":
                requireId(sender, args);
                upload(server, sender, args[2]);
                return;
            case "download":
                requireId(sender, args);
                download(server, sender, args[2]);
                return;
            default:
                throw new WrongUsageException(TraceLang.format("aevm.trace.usage"));
        }
    }

    private static void requireId(ICommandSender sender, String[] args) throws WrongUsageException {
        if (args.length < 3) {
            throw new WrongUsageException(TraceLang.format("aevm.trace.usage"));
        }
    }

    // ------------------------------------------------------------------
    // record

    private void record(ICommandSender sender, String[] args) throws CommandException, PlayerNotFoundException {
        String mode = args.length >= 3 ? args[2] : "next";
        switch (mode) {
            case "next": {
                UUID me = asPlayer(sender).getUniqueID();
                if (!TraceSessions.armNext(me)) {
                    send(sender, TraceLang.format("aevm.trace.record.busy", TraceSessions.status()));
                    return;
                }
                send(sender, TraceLang.format("aevm.trace.record.next"));
                return;
            }
            case "on": {
                requireOp(sender);
                if (!TraceSessions.armWindow()) {
                    send(sender, TraceLang.format("aevm.trace.record.busy", TraceSessions.status()));
                    return;
                }
                send(sender, TraceLang.format("aevm.trace.record.window"));
                return;
            }
            case "off": {
                send(sender, TraceLang.format(TraceSessions.disarm()
                        ? "aevm.trace.record.off" : "aevm.trace.record.nothing"));
                return;
            }
            default:
                throw new WrongUsageException(TraceLang.format("aevm.trace.usage"));
        }
    }

    private void requireOp(ICommandSender sender) throws CommandException {
        if (!isOp(sender)) {
            throw new CommandException(TraceLang.format("aevm.trace.op-only"));
        }
    }

    // ------------------------------------------------------------------
    // list

    private void list(ICommandSender sender, String[] args) throws CommandException, PlayerNotFoundException {
        boolean all = false;
        int page = 1;
        for (int i = 2; i < args.length; i++) {
            if ("all".equals(args[i])) {
                all = true;
            } else {
                try {
                    page = Integer.parseInt(args[i]);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        boolean opView = all && isOp(sender);
        UUID self = getCommandSenderAsPlayer(sender).getUniqueID();
        String selfToken = TraceSessions.vault().tokenForPlayer(self, "");

        List<Path> files = traceFiles();
        List<ITextComponent> lines = new ArrayList<>();
        for (Path p : files) {
            TraceLoader.Result r = loadQuiet(p);
            if (r == null) {
                continue;
            }
            String ownerToken = requestPlayerToken(r.file);
            if (!opView && (ownerToken == null || !ownerToken.equals(selfToken))) {
                continue;
            }
            lines.add(renderLine(p, r.file, opView, ownerToken));
        }
        int pages = Math.max(1, (lines.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page < 1 || page > pages) {
            page = 1;
        }
        send(sender, TraceLang.format(opView ? "aevm.trace.list.header-all" : "aevm.trace.list.header",
                lines.size(), page, pages));
        int from = (page - 1) * PAGE_SIZE;
        for (int i = from; i < Math.min(from + PAGE_SIZE, lines.size()); i++) {
            send(sender, lines.get(i));
        }
        if (page == pages) {
            send(sender, TraceLang.format("aevm.trace.list.footer"));
        } else {
            send(sender, TraceLang.format("aevm.trace.list.page", page + 1));
        }
    }

    private ITextComponent renderLine(Path p, TraceFile f, boolean withSource, String ownerToken) {
        String id = traceIdOf(p);
        String statusKey = "aevm.trace.status." + lastStatus(f);
        String request = requestSummary(f);
        // SHORT row: id + status (+ source). The request details ride in the
        // tooltip — long wrapping rows were exactly where the live client
        // lost every component style, while short styled parts survived.
        // NO leading '#': the ids are date-based and '#202609' parses as a
        // hex color (#RRGGBB = near-black) in chat pipelines that support
        // inline hex colors — the summary rendered in that parsed color
        // instead of the intended light purple.
        StringBuilder sb = new StringBuilder("trace ").append(id)
                .append(' ').append(TraceLang.format(statusKey)).append("  ");
        if (withSource) {
            sb.append(sourceLabel(f, ownerToken)).append("  ");
        }
        // main line: click pre-fills the summary command. The color rides IN
        // THE TEXT as a legacy § code rather than on the component style:
        // live CatServer clients lost component styles on this row (styles on
        // the root AND on a long wrapping sibling) while the short styled
        // buttons survived — a § code is part of the string and survives
        // every re-render. LIGHT_PURPLE keeps the summary readable on
        // translucent chat backgrounds.
        TextComponentString root = new TextComponentString("");
        TextComponentString summary = new TextComponentString(
                TextFormatting.LIGHT_PURPLE.toString() + sb.toString());
        Style main = summary.getStyle();
        main.setColor(TextFormatting.LIGHT_PURPLE);
        main.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND,
                "/ae2vm trace show " + id));
        StringBuilder hover = new StringBuilder(coloredRequest(f));
        if (withSource) {
            hover.append('\n').append("source: ").append(sourceLabel(f, ownerToken));
        }
        hover.append('\n').append(TraceLang.format("aevm.trace.btn.show-hover"));
        main.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new TextComponentString(hover.toString())));
        root.appendSibling(summary);
        // direct-execution action buttons (permissions enforced server-side)
        TextComponentString up = new TextComponentString(TraceLang.format("aevm.trace.btn.upload"));
        Style upStyle = up.getStyle();
        upStyle.setColor(TextFormatting.GOLD);
        upStyle.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                "/ae2vm trace upload " + id));
        upStyle.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new TextComponentString(TraceLang.format("aevm.trace.btn.upload-hover"))));
        root.appendSibling(up);
        root.appendSibling(new TextComponentString(" "));
        TextComponentString dl = new TextComponentString(TraceLang.format("aevm.trace.btn.download"));
        Style dlStyle = dl.getStyle();
        dlStyle.setColor(TextFormatting.GREEN);
        dlStyle.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                "/ae2vm trace download " + id));
        dlStyle.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new TextComponentString(TraceLang.format("aevm.trace.btn.download-hover"))));
        root.appendSibling(dl);
        return root;
    }

    private String requestSummary(TraceFile f) {
        TraceEvent req = first(f, TraceSegment.CALC, "REQUEST");
        String what = req == null ? "?" : req.f.get("what");
        String count = req == null ? "?" : req.f.get("count");
        return count + "x" + displayName(what);
    }

    /** The request summary with the count and the item colored for the tooltip. */
    private String coloredRequest(TraceFile f) {
        TraceEvent req = first(f, TraceSegment.CALC, "REQUEST");
        String what = req == null ? "?" : req.f.get("what");
        String count = req == null ? "?" : req.f.get("count");
        return TextFormatting.GOLD.toString() + count + TextFormatting.RESET.toString()
                + " x " + TextFormatting.AQUA.toString() + displayName(what)
                + TextFormatting.RESET.toString();
    }

    private String displayName(String token) {
        StackIdentity id = TraceSessions.vault().stackForToken(token);
        if (id == null) {
            return token;
        }
        return id.id + (id.damage != 0 ? "/" + id.damage : "");
    }

    private String sourceLabel(TraceFile f, String ownerToken) {
        TraceEvent req = first(f, TraceSegment.CALC, "REQUEST");
        if (req == null) {
            return "?";
        }
        if ("player".equals(req.f.get("source"))) {
            String name = req.f.get("player") == null ? "?"
                    : TraceSessions.vault().playerNameForToken(req.f.get("player"));
            return name == null ? req.f.get("player") : name;
        }
        String dev = req.f.get("device");
        String desc = dev == null ? "?" : TraceSessions.vault().deviceDescForToken(dev);
        return desc == null ? dev : desc;
    }

    private String lastStatus(TraceFile f) {
        String status = "abandoned";
        for (TraceSegment s : f.segments) {
            for (int i = s.headSkip; i < s.events.size(); i++) {
                TraceEvent e = s.events.get(i);
                if ("COMMIT".equals(e.type)) {
                    String st = e.f.get("status");
                    if (st != null) {
                        status = st;
                    }
                }
            }
        }
        return status;
    }

    private TraceEvent first(TraceFile f, String phase, String type) {
        for (TraceSegment s : f.segments) {
            if (!phase.equals(s.phase)) {
                continue;
            }
            for (int i = s.headSkip; i < s.events.size(); i++) {
                if (type.equals(s.events.get(i).type)) {
                    return s.events.get(i);
                }
            }
        }
        return null;
    }

    private String requestPlayerToken(TraceFile f) {
        TraceEvent req = first(f, TraceSegment.CALC, "REQUEST");
        return req == null ? null : req.f.get("player");
    }

    // ------------------------------------------------------------------
    // show

    private void show(ICommandSender sender, String idPart) throws CommandException {
        Path p = resolveOwned(sender, idPart);
        TraceLoader.Result r = loadQuiet(p);
        if (r == null || r.file == null) {
            send(sender, TraceLang.format("aevm.trace.show.nosuch", idPart));
            return;
        }
        TraceFile f = r.file;
        TraceEvent req = first(f, TraceSegment.CALC, "REQUEST");
        String what = req == null ? "?" : displayName(req.f.get("what"));
        String count = req == null ? "?" : req.f.get("count");
        int events = countEvents(f);
        send(sender, TraceLang.format("aevm.trace.show.header",
                traceIdOf(p), what, count, f.meta.get("aevm"), events));
        if (f.plan != null) {
            send(sender, TraceLang.format("aevm.trace.show.plan",
                    f.plan.patternTimes.size(), f.plan.used.size(), f.plan.missing.size(),
                    f.plan.emitted.size(), f.plan.simulation));
            if (!f.plan.missing.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < Math.min(5, f.plan.missing.size()); i++) {
                    StackEntry e = f.plan.missing.get(i);
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(e.count).append('x').append(displayName(e.spec.token));
                }
                send(sender, TraceLang.format("aevm.trace.show.missing", sb));
            }
        }
        if (!r.intact()) {
            send(sender, "[integrity: " + (r.chainBrokenAt >= 0 ? "chain-broken@" + r.chainBrokenAt : "")
                    + (r.payloadTruncated ? " payload-edited" : "") + "]");
        }
        send(sender, TraceLang.format("aevm.trace.show.file", p.toString()));
    }

    // ------------------------------------------------------------------
    // upload / download

    private void upload(MinecraftServer server, ICommandSender sender, String idPart)
            throws CommandException, PlayerNotFoundException {
        if (!AE2VMConfig.traceUploadEnabled) {
            throw new CommandException(TraceLang.format("aevm.trace.upload.disabled"));
        }
        Path p = findFile(idPart);
        if (p == null) {
            throw new CommandException(TraceLang.format("aevm.trace.show.nosuch", idPart));
        }
        // players upload only their OWN traces; ops upload anything
        if (!isOp(sender)) {
            UUID self = getCommandSenderAsPlayer(sender).getUniqueID();
            TraceLoader.Result r = loadQuiet(p);
            String owner = r == null ? null : requestPlayerToken(r.file);
            String selfToken = TraceSessions.vault().tokenForPlayer(self, "");
            if (owner == null || !owner.equals(selfToken)) {
                throw new CommandException(TraceLang.format("aevm.trace.upload.not-yours"));
            }
        }
        TraceUpload.start(server, sender, p);
    }

    private void download(MinecraftServer server, ICommandSender sender, String idPart)
            throws CommandException {
        Path p = resolveOwned(sender, idPart);
        TraceDownload.start(server, sender, p);
    }

    // ------------------------------------------------------------------
    // shared helpers

    private static void send(ICommandSender sender, String line) {
        sender.sendMessage(new TextComponentString(line));
    }

    private static void send(ICommandSender sender, ITextComponent line) {
        sender.sendMessage(line);
    }

    private static int countEvents(TraceFile f) {
        int n = 0;
        for (TraceSegment s : f.segments) {
            n += s.liveCount();
        }
        return n;
    }

    private List<Path> traceFiles() throws CommandException {
        Path dir = TraceStore.tracesDir();
        if (dir == null || !Files.isDirectory(dir)) {
            throw new CommandException(TraceLang.format("aevm.trace.show.nosuch", "(no trace dir)"));
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().endsWith(".aevmtrace.json.gz"))
                    .forEach(files::add);
        } catch (IOException e) {
            throw new CommandException("io: " + e);
        }
        files.sort(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed());
        return files;
    }

    private TraceLoader.Result loadQuiet(Path p) {
        try {
            return TraceLoader.load(p);
        } catch (IOException e) {
            Log.LOG.debug("[AE2-VM] unreadable trace {}", p, e);
            return null;
        }
    }

    private String traceIdOf(Path p) {
        String name = p.getFileName().toString();
        String stripped = name
                .replace("trace-", "")
                .replace(".aevmtrace.json.gz", "");
        return stripped;
    }

    private Path resolveOwned(ICommandSender sender, String idPart) throws CommandException, PlayerNotFoundException {
        Path p = findFile(idPart);
        if (p == null) {
            throw new CommandException(TraceLang.format("aevm.trace.show.nosuch", idPart));
        }
        if (!isOp(sender)) {
            UUID self = getCommandSenderAsPlayer(sender).getUniqueID();
            TraceLoader.Result r = loadQuiet(p);
            String owner = r == null ? null : requestPlayerToken(r.file);
            String selfToken = TraceSessions.vault().tokenForPlayer(self, "");
            if (owner == null || !owner.equals(selfToken)) {
                throw new CommandException(TraceLang.format("aevm.trace.show.nosuch", idPart));
            }
        }
        return p;
    }

    private Path findFile(String idPart) throws CommandException {
        for (Path p : traceFiles()) {
            if (traceIdOf(p).contains(idPart)) {
                return p;
            }
        }
        return null;
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender,
                                                    String[] args, @Nullable BlockPos pos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "trace");
        }
        if (args.length == 2) {
            return getListOfStringsMatchingLastWord(args, "record", "list", "show", "upload", "download");
        }
        if (args.length == 3 && "record".equals(args[1])) {
            return getListOfStringsMatchingLastWord(args, "next", "on", "off");
        }
        if (args.length == 3 && "list".equals(args[1]) && isOp(sender)) {
            return getListOfStringsMatchingLastWord(args, "all");
        }
        return Collections.emptyList();
    }
}
