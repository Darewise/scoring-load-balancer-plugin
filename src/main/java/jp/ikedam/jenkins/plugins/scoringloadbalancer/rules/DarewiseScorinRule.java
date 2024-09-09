// Copyright Darewise Entertainment. All Rights Reserved.(c)
package jp.ikedam.jenkins.plugins.scoringloadbalancer.rules;

import hudson.EnvVars;
import hudson.model.Descriptor;
import hudson.model.Job;
import hudson.model.Label;
import hudson.model.Run;
import hudson.model.labels.LabelAtom;
import hudson.model.labels.LabelExpression;
import hudson.model.queue.MappingWorksheet;
import hudson.remoting.VirtualChannel;
import java.io.File;
import java.io.IOException;
import java.util.Set;
import jenkins.security.MasterToSlaveCallable;
import org.kohsuke.stapler.DataBoundConstructor;
import hudson.Extension;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.Result;
import hudson.model.queue.MappingWorksheet.ExecutorChunk;
import hudson.model.queue.SubTask;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import jp.ikedam.jenkins.plugins.scoringloadbalancer.ScoringLoadBalancer;
import jp.ikedam.jenkins.plugins.scoringloadbalancer.ScoringRule;
import jp.ikedam.jenkins.plugins.scoringloadbalancer.preferences.BestNodesJobProperty;
import jp.ikedam.jenkins.plugins.scoringloadbalancer.preferences.BuildPreference;
import jp.ikedam.jenkins.plugins.scoringloadbalancer.preferences.BuildPreferenceJobProperty;

/**
 *
 * @author pawel
 */
public class DarewiseScorinRule extends ScoringRule {

    private static final Logger LOGGER = Logger.getLogger(DarewiseScorinRule.class.getName());

    @DataBoundConstructor
    public DarewiseScorinRule() {
    }

    @Override
    public boolean updateScores(
            Queue.Task task,
            MappingWorksheet.WorkChunk wc,
            MappingWorksheet.Mapping m,
            ScoringLoadBalancer.NodesScore nodesScore)
            throws Exception {
        LOGGER.info("DarewiseScorinRule: start");
        LOGGER.log(Level.INFO, "DW Task: {0}", task.getClass().getName());
        LOGGER.log(Level.INFO, "DW wc: {0}", wc.getClass().getName());
        LOGGER.log(Level.INFO, "DW m: {0}", m.getClass().getName());
        Queue.Task originalTask = task.getOwnerTask();
        LOGGER.log(Level.INFO, "DW originalTask: {0}", originalTask.getClass().getName());
        String label = "generic";
        long minFreeSpace = 100000000L;
        String jobName = null;
        if (originalTask instanceof WorkflowJob) {
            WorkflowJob workflowJob = (WorkflowJob) originalTask;
            Job<?, ?> job = (Job<?, ?>) originalTask;
            BestNodesJobProperty property = job.getProperty(BestNodesJobProperty.class);
            label = property.getLabel();
            minFreeSpace = property.getMinFreeSpace() * 1000000000L;
            jobName = job.getFullName();
            LOGGER.log(Level.INFO, "DW workflowJob: {0}, job: {1}", new Object[]{workflowJob.getClass().getName(), job.getClass().getName()});
            LOGGER.log(Level.INFO, String.format("Looking for nodes with label: %s on job: %s", label, jobName));
        } else {
            LOGGER.log(Level.SEVERE, "Expected WorkflowJob ");
            return false;
        }
//if (parent instanceof PlaceholderTask) {
//            PlaceholderTask placeholderTask = (PlaceholderTask) task;
//}
        LOGGER.log(Level.INFO, "Subtask runnig on label:{0}", wc.assignedLabel != null ? wc.assignedLabel.toString() : "any");
        for (SubTask subtask : wc) {
            BuildPreferenceJobProperty prefs = getBuildPreferenceJobProperty(subtask);
            if (prefs == null || prefs.getBuildPreferenceList() == null) {
                LOGGER.log(Level.INFO, "No prefs for: {0}", subtask.toString());
                continue;
            }

            for (BuildPreference pref : prefs.getBuildPreferenceList()) {

                LOGGER.log(Level.INFO, "PREF: {0}", pref.toString());
                try {
                    Label l = LabelExpression.parseExpression(pref.getLabelExpression());
                    for (Node node : nodesScore.getNodes()) {
                        if (!l.contains(node)) {
                            continue;
                        }
                        // nodesScore.addScore(node, pref.getPreference() * getProjectPreferenceScale());
                    }
                } catch (IllegalArgumentException e) {
                    LOGGER.log(
                            Level.WARNING,
                            String.format(
                                    "Skipped an invalid label: %s (configured in %s)",
                                    pref.getLabelExpression(), subtask.toString()),
                            e);
                }
            }
        }
        LOGGER.log(Level.INFO, "Looking for nodes with label: '{0}'", label);
        //long minFreeSpace = property.getMinFreeSpace();
        for (Node node : nodesScore.getNodes()) {
            VirtualChannel channel = node.getChannel();
            Map<String, Object> nodeinfo = getNodeInfo(channel, jobName);
            LOGGER.log(Level.INFO, "NodeInfo: {0}", nodeinfo.toString());
            if (hasMatchingLabel(node, label)) {
                LOGGER.log(Level.INFO, "Label found on node: '{0}'", node.toString());
                int score = hasSufficientFreeSpace(node, minFreeSpace);
                int scoreadj = score < 50 ? score : score * 3;
                nodesScore.addScore(node, scoreadj, "free space");
                LOGGER.log(Level.INFO, "Free space found on node: {0}", node.toString());
                int cpuCount = (int) nodeinfo.get("cpuCount");
                int cpuScore = 100 * cpuCount / 16;
                int cpuScoreadj = cpuScore * 2;
                nodesScore.addScore(node, cpuScoreadj, "cpu");

            } else {
                nodesScore.addScore(node, -100000, "no label");
            }
        }
        for (ExecutorChunk ec : nodesScore.getExecutorChunks()) {
            if (isPreviousBuildSuccessful(task)) {
                nodesScore.addScore(ec, 1, "prev build ok");
            }
        }
        LOGGER.info("Ended updating scores");
        LOGGER.info("");
        return true;
    }

    private boolean isPreviousBuildSuccessful(Queue.Task task) {
        if (task instanceof Job) {
            Job job = (Job) task;
            Run lastBuild = job.getLastBuild();
            if (lastBuild != null) {
                return lastBuild.getResult() == Result.SUCCESS;
            }
        }
        return false;
    }

    private boolean hasMatchingLabel(Node node, String label) {
        Set<LabelAtom> nodeLabels = node.getAssignedLabels();
        final LabelAtom l = new LabelAtom(label);
        return nodeLabels.contains(l);
    }

    /*
    returns value between 0 and 100
     */
    private int hasSufficientFreeSpace(Node node, long freeSpaceThreshold) {
        LOGGER.log(Level.INFO, "Checkin hasSufficientFreeSpace for node: {0}", node.toString());
        VirtualChannel channel = node.getChannel();
        long freeSpace;
        try {
            freeSpace = getHomeDirectory(channel);
            LOGGER.log(Level.INFO, "hasSufficientFreeSpace freeSpace: {0}", freeSpace);
            if (freeSpace >= 2 * freeSpaceThreshold) {
                return 100;
            }
            return (int) (50.0 * ((float) freeSpace / (float) freeSpaceThreshold));
        } catch (IOException ex) {
        } catch (InterruptedException ex) {
        }
        return 0;
    }

    public static Map<String, Object> getNodeInfo(VirtualChannel channel, String jobName) throws InterruptedException, IOException {
        Map<String, Object> result = new HashMap<>();
        if (channel == null) {
            result.put("online", false);
            return result;
        }
        result = channel.call(new GetNodeInfo(jobName));
        result.put("online", true);
        return result;
    }

    private static class GetNodeInfo extends MasterToSlaveCallable<Map<String, Object>, IOException> {

        private final String jobName;

        private GetNodeInfo(String jobName) {
            this.jobName = jobName;
        }

        @Override
        public Map<String, Object> call() throws IOException {
            Map<String, Object> result = new HashMap<>();
            Runtime runtime = Runtime.getRuntime();
            OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            //MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
            result.put("cpuCount", runtime.availableProcessors());
            result.put("totalMemory", runtime.totalMemory());
            result.put("freeMemory", runtime.freeMemory());
            result.put("systemLoadAverage", os.getSystemLoadAverage());
            return result;
        }
    }

    public static Long getHomeDirectory(VirtualChannel channel) throws InterruptedException, IOException {
        if (channel == null) {
            return 0L;
        }
        return channel.call(new GetFreeDiskSpace());
    }

    private BuildPreferenceJobProperty getBuildPreferenceJobProperty(SubTask subtask) {
        SubTask task = subtask;
        if (!(subtask instanceof Job)) {
            Queue.Task originalTask = subtask.getOwnerTask();
            LOGGER.log(Level.INFO, "getBuildPreferenceJobProperty: subtask is not instanceof Job but: {0}", subtask.toString());
            LOGGER.log(Level.INFO, "getBuildPreferenceJobProperty: subtask.getDisplayName()={0}", subtask.getDisplayName());
            if (originalTask instanceof WorkflowJob) {
                task = originalTask;
            } else {
                LOGGER.log(Level.INFO, "DarewiseScorinRule: subtask.getOwnerTask() is not instanceof WorkflowJob but: {0}", originalTask.toString());
                return null;
            }
        }

        Job<?, ?> job = (Job<?, ?>) task;
        return job.getProperty(BuildPreferenceJobProperty.class);
    }

    private static class GetFreeDiskSpace extends MasterToSlaveCallable<Long, IOException> {

        @Override
        public Long call() throws IOException {
            return new File(System.getProperty("user.home")).getFreeSpace();
        }
    }

    /**
     * Obtains the environment variables of a remote peer.
     *
     */
    private static final class GetEnvVars extends MasterToSlaveCallable<EnvVars, RuntimeException> {

        @Override
        public EnvVars call() {
            return new EnvVars(EnvVars.masterEnvVars);
        }

        private static final long serialVersionUID = 1L;
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<ScoringRule> {

        /**
         * Returns the name to display.
         *
         * Displayed in System Configuration page, as a name of a scoring rule.
         *
         * @return the name to display
         * @see hudson.model.Descriptor#getDisplayName()
         */
        @Override
        public String getDisplayName() {
            return "DarewiseScorinRule";
        }
    }
}
