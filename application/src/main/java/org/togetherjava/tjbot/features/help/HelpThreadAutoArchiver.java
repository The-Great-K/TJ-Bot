package org.togetherjava.tjbot.features.help;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.ForumChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.utils.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.togetherjava.tjbot.features.Routine;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Routine, which periodically checks all help threads and archives them if there has not been any
 * recent activity.
 */
public final class HelpThreadAutoArchiver implements Routine {
    private static final Logger logger = LoggerFactory.getLogger(HelpThreadAutoArchiver.class);
    private static final int SCHEDULE_MINUTES = 60;
    private static final Duration ARCHIVE_AFTER_INACTIVITY_OF = Duration.ofHours(12);

    private final HelpSystemHelper helper;

    /**
     * Creates a new instance.
     *
     * @param helper the helper to use
     */
    public HelpThreadAutoArchiver(HelpSystemHelper helper) {
        this.helper = helper;
    }

    @Override
    public Schedule createSchedule() {
        return new Schedule(ScheduleMode.FIXED_RATE, 0, SCHEDULE_MINUTES, TimeUnit.MINUTES);
    }

    @Override
    public void runRoutine(JDA jda) {
        jda.getGuildCache().forEach(this::autoArchiveForGuild);
    }

    private void autoArchiveForGuild(Guild guild) {
        Optional<ForumChannel> maybeHelpForum = helper
            .handleRequireHelpForum(guild, channelPattern -> logger.warn(
                    "Unable to auto archive help threads, did not find a help forum matching the configured pattern '{}' for guild '{}'",
                    channelPattern, guild.getName()));

        if (maybeHelpForum.isEmpty()) {
            return;
        }

        logger.debug("Auto archiving of help threads");

        List<ThreadChannel> activeThreads = helper.getActiveThreadsIn(maybeHelpForum.orElseThrow());
        logger.debug("Found {} active questions", activeThreads.size());

        Instant archiveAfterMoment = computeArchiveAfterMoment();
        activeThreads.stream()
            .filter(activeThread -> shouldBeArchived(activeThread, archiveAfterMoment))
            .forEach(this::autoArchiveForThread);
    }

    private Instant computeArchiveAfterMoment() {
        return Instant.now().minus(ARCHIVE_AFTER_INACTIVITY_OF);
    }

    private void autoArchiveForThread(ThreadChannel threadChannel) {
        logger.debug("Auto archiving help thread {}", threadChannel.getId());

        String linkHowToAsk = "https://stackoverflow.com/help/how-to-ask";

        MessageEmbed embed = new EmbedBuilder()
            .setDescription(
                    """
                            ### __Your question has been closed due to inactivity.__
                            If it was not resolved yet, feel free to just post a message below
                            to reopen it, or create a new thread.

                            If no one is responding to your question try **improving the question quality**.
                            You can do this by providing details, context, all *relevant* code, any
                            errors, and examples of your issue/question."""
                        .formatted(linkHowToAsk))
            .setColor(HelpSystemHelper.AMBIENT_COLOR)
            .build();

        handleArchiveFlow(threadChannel, embed);
    }

    private static boolean shouldBeArchived(ThreadChannel channel, Instant archiveAfterMoment) {
        Instant lastActivity =
                TimeUtil.getTimeCreated(channel.getLatestMessageIdLong()).toInstant();

        return !channel.isPinned() && lastActivity.isBefore(archiveAfterMoment);
    }

    private void handleArchiveFlow(ThreadChannel threadChannel, MessageEmbed embed) {
        helper.getAuthorByHelpThreadId(threadChannel.getIdLong())
            .ifPresentOrElse(authorId -> triggerArchiveFlow(threadChannel, authorId, embed),
                    () -> triggerAuthorIdNotFoundArchiveFlow(threadChannel, embed));
    }

    private void triggerArchiveFlow(ThreadChannel threadChannel, long authorId,
            MessageEmbed embed) {

        Function<Member, RestAction<Message>> sendEmbedWithMention =
                member -> threadChannel.sendMessage(member.getAsMention()).addEmbeds(embed);

        Supplier<RestAction<Message>> sendEmbedWithoutMention =
                () -> threadChannel.sendMessageEmbeds(embed);

        threadChannel.getGuild()
            .retrieveMemberById(authorId)
            .mapToResult()
            .flatMap(authorResults -> {
                if (authorResults.isFailure()) {
                    logger.info(
                            "Trying to archive a thread ({}), but OP ({}) left the server, sending embed without mention",
                            threadChannel.getId(), authorId, authorResults.getFailure());

                    return sendEmbedWithoutMention.get();
                }

                return sendEmbedWithMention.apply(authorResults.get());
            })
            .flatMap(any -> threadChannel.getManager().setArchived(true))
            .queue();
    }

    private void triggerAuthorIdNotFoundArchiveFlow(ThreadChannel threadChannel,
            MessageEmbed embed) {

        logger.info(
                "Was unable to find a matching thread for id: {} in DB, archiving thread without mentioning OP",
                threadChannel.getId());
        threadChannel.sendMessageEmbeds(embed)
            .flatMap(sentEmbed -> threadChannel.getManager().setArchived(true))
            .queue();
    }
}
