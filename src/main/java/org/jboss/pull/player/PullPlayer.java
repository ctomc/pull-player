package org.jboss.pull.player;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;

import org.jboss.dmr.ModelNode;

/**
 * @author Tomaz Cerar (c) 2013 Red Hat Inc.
 */
public class PullPlayer {
    private static final Pattern okToTest = Pattern.compile(".*ok\\W+to\\W+test.*", Pattern.DOTALL);
    private static final Pattern retest = Pattern.compile(".*retest\\W+this\\W+please.*", Pattern.DOTALL);
    private static final int PAGE_LIMIT = 10000;
    private final GitHubApi gitHubApi;
    private final TeamCityApi teamCityApi;
    private final LabelProcessor labelProcessor;
    //private final PersistentList queue = PersistentList.loadList("queue");
    private String githubLogin;

    protected PullPlayer(final boolean dryRun) throws Exception {
        String teamcityHost = Util.require("teamcity.host");
        String teamcityPort = Util.require("teamcity.port");
        String teamcityBuildType = Util.require("teamcity.build.type");
        githubLogin = GitHubApi.USERNAME;
        String user = Util.require("teamcity.user");
        String password = Util.require("teamcity.password");
        gitHubApi = new GitHubApi(dryRun);
        labelProcessor = new LabelProcessor();
        teamCityApi = new TeamCityApi("http://" + teamcityHost + ":" + teamcityPort + "/httpAuth", user, password, teamcityBuildType, dryRun);
    }

    static String getTime() {
        DateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
        Date date = new Date();
        return dateFormat.format(date);
    }


    boolean noBuildPending(int pull, List queue) {
        return !queue.contains(pull);
    }

    private void processPulls(PersistentList whiteList, PersistentList adminList, List<ModelNode> nodes) {
        final List<Integer> queue = teamCityApi.getQueuedBuilds();
        for (ModelNode pull : nodes) {
            int pullNumber = pull.get("number").asInt();

            String user = pull.get("user", "login").asString();
            String sha1 = pull.get("head", "sha").asString();
            String branch = pull.get("base", "ref").asString();

            if (sha1 == null) {
                System.err.println("Could not get sha1 for pull: " + pullNumber);
                continue;
            }

            // Add the pull to the label processor
            labelProcessor.add(pull);

            System.out.printf("number: %d login: %s sha1: %s\n", pullNumber, user, sha1);
            String job = Jobs.getCompletedJob(sha1);

            boolean retrigger = false;
            boolean whitelistNotify = true;
            for (Comment comment : gitHubApi.getComments(pullNumber)) {
                if (githubLogin.equals(comment.user) && comment.comment.contains("triggering")) {
                    retrigger = false;
                    continue;
                }

                if (githubLogin.equals(comment.user) && comment.comment.contains("running")) {
                    retrigger = false;
                    continue;
                }

                if (githubLogin.equals(comment.user) && comment.comment.contains("verify this patch")) {
                    whitelistNotify = false;
                    continue;
                }

                if (whiteList.has(user) && whiteList.has(comment.user) && job != null && retest.matcher(comment.comment).matches()) {
                    retrigger = true;
                    continue;
                }

                if (!whiteList.has(user) && adminList.has(comment.user) && okToTest.matcher(comment.comment).matches()) {
                    whiteList.add(user);
                    retrigger = true;
                    continue;
                }
            }
            System.out.println("retrigger = " + retrigger);
            if (job == null & !verifyWhitelist(whiteList, user, pullNumber, whitelistNotify)) {
                System.out.println("User not whitelisted, user: " + user);
                continue;
            }
            TeamCityBuild build = teamCityApi.findBuild(pullNumber, sha1);

            if (retrigger) {
                if (queue.contains(pullNumber)){
                    System.out.println("Build already queued");
                }else if (build.isRunning()){
                    System.out.println("Build already running");
                }else{
                    Jobs.remove(sha1);
                }
                continue;
            }

            if (job != null) {
                System.out.println("Already done: " + pullNumber);
                continue;
            }

            long cur = System.currentTimeMillis();

            System.out.println("\tTime to find build: " + (System.currentTimeMillis() - cur));
            if (build != null) {
                if (build.getStatus() != null) {
                    Jobs.storeCompletedJob(sha1, pullNumber, build.getBuild());
                } else {
                    System.out.println("In progress, skipping: " + pullNumber);
                }
            } else if (noBuildPending(pullNumber, queue)) {
                teamCityApi.triggerJob(pullNumber, sha1);
            } else {
                System.out.println("Pending build, skipping: " + pullNumber);
            }
        }
        // Process the labels after each pull has been added
        labelProcessor.process();
    }

    private boolean verifyWhitelist(PersistentList whiteList, String user, int pullNumber, boolean notify) {
        if (!whiteList.has(user)) {
            System.out.printf("Skipping %s\n", user);
            if (notify) {
                gitHubApi.postComment(pullNumber, "Can one of the admins verify this patch?");
            }
            return false;
        }

        return true;
    }

    protected void checkPullRequests() {
        final PersistentList whiteList = PersistentList.loadList("white-list");
        final PersistentList adminList = PersistentList.loadList("admin-list");

        for (int page = 1; ; page++) {


            List<ModelNode> nodes = gitHubApi.getPullRequests(page);
            if (nodes.size() == 0) { break; }

            processPulls(whiteList, adminList, nodes);
            if (page > PAGE_LIMIT) {
                throw new IllegalStateException("Exceeded page limit");
            }
        }

    }
}
