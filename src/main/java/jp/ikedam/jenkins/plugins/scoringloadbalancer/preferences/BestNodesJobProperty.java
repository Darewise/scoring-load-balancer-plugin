package jp.ikedam.jenkins.plugins.scoringloadbalancer.preferences;

import hudson.Extension;
import hudson.model.Job;
import hudson.model.JobProperty;
import hudson.model.JobPropertyDescriptor;
import hudson.util.FormValidation;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

public class BestNodesJobProperty extends JobProperty<Job<?, ?>> {

    private boolean enableDarewiseScoring;
    private final String label;
    private final long minFreeSpace;

    @DataBoundConstructor
    public BestNodesJobProperty(boolean enableDarewiseScoring, String label, long minFreeSpace) {
        this.enableDarewiseScoring = enableDarewiseScoring;
        this.label = label;
        this.minFreeSpace = minFreeSpace;
    }

    @DataBoundSetter
    public void setEnableDarewiseScoring(boolean enableDarewiseScoring) {
        this.enableDarewiseScoring = enableDarewiseScoring;
    }

    public boolean isEnableDarewiseScoring() {
        return enableDarewiseScoring;
    }

    public String getLabel() {
        return label;
    }

    public long getMinFreeSpace() {
        return minFreeSpace;
    }

    @Symbol("bestNodes")
    @Extension
    public static class DescriptorImpl extends JobPropertyDescriptor {

        @Override
        public String getDisplayName() {
            return "Best Nodes Job Property";
        }

        public FormValidation doCheckLabel(@QueryParameter String value, @QueryParameter boolean enableDarewiseScoring) {
            if (enableDarewiseScoring && value.length() == 0) {
                return FormValidation.error("Please set default node");
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckMinFreeSpace(@QueryParameter String value) {
            try {
                int intValue = Integer.parseInt(value);
                if (intValue < 0) {
                    return FormValidation.error("Please enter a positive integer");
                }
            } catch (NumberFormatException e) {
                return FormValidation.error("Please enter a valid integer");
            }
            return FormValidation.ok();
        }
    }
}
