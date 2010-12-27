/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package hudson.plugins.robot;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.Descriptor;
import hudson.model.Hudson;
import hudson.model.Project;
import hudson.model.TaskListener;
import hudson.tasks.Builder;
import hudson.tasks.CommandInterpreter;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.ServletException;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.export.Exported;

/**
 *
 * @author Rene Fiedler
 *
 * The following section(s) describes the class CommandInterpreter, because it is
 * ( in fact )  not documented.
 *
 * The constructor (of CommandInterpreter) takes a String and saves the content in "command".
 * During the invocation of the Build-Step (function:perform), the content of
 * "command" will be saved in a temp-file (name of the file is: "hudson+getFileExtension()").
 * After that, the function buildCommandLine with the Path of the created temp-file will be called.
 * This function has to return the command and the arguments of the Robot Framework
 * (pybot), which will later be executed in the workspace (with the corresponding environment- see: Listener ).
 *
 *  For the execution of the pybot-command it is not necessary to copy oder save any content
 *  to the temp-file, so an empty string will be passed to the constructor of the super class.
 *  The function buildCommandline( Tempfile ) then will then build the command, which
 *  is executed.  
 *
 * TODO To only be able to add ONE pybot-BuildStep per job/project
 *      (adjust newInstance, return "something" to get hudson not to add this Build-Step)
 *
 */
public class RobotBuildStep extends CommandInterpreter {

    private static final Logger logger = Logger.getLogger(RobotBuildStep.class.getName());
    //test whether String is a beginning of a path ( absolute and relative)
    private static String pathPattern = "((\\.{2})|" + //  test  ".."
            "/|" + //  test: "/"
            "([a-zA-Z]:\\\\)|" + //  test: "X:\"
            "(\\\\{2}))";//  test: "\\"
    private static String debugFilePatternPybot = "^Debug:\\s{1,}";
    private static String outputFilePatternPybot = "^Output:\\s{1,}";
    private static String summaryFilePatternPybot = "Summary:\\s{1,}";
    private static String reportFilePatternPybot = "^Report:\\s{1,}";
    private static String logFilePatternPybot = "^Log:\\s{1,}";
    private boolean saveDebug = false;
    private boolean saveSummary = false;
    private String robotFilePath;
    private String setTags;
    private String runTestCasesByName;
    private String runTestCasesByTag;
    private String excludeTestCases;
    private String nonCriticalTestCases;
    private String variableFile;
    private String argumentFile;
    private String outputDir;
    private String summaryFile;
    private String debugFile;

    private RobotBuildStep(String robotFilePath, String outputDir, String setTags,
                           String runTestCasesByName, String runTestCasesByTag,
                           String excludeTestCases, String nonCriticalTestCases,
                           String variableFile, String argumentFile, String summaryFile,
                           String debugFile) {

        super("");
        this.robotFilePath = robotFilePath.trim();
        this.variableFile = variableFile.trim();
        this.setTags = setTags.trim();
        this.runTestCasesByName = runTestCasesByName.trim();
        this.runTestCasesByTag = runTestCasesByTag.trim();
        this.excludeTestCases = excludeTestCases.trim();
        this.nonCriticalTestCases = nonCriticalTestCases.trim();
        this.argumentFile = argumentFile.trim();
        if (summaryFile != null) {
            this.saveSummary = true;
            this.summaryFile = summaryFile.trim();
        }
        if (debugFile != null) {
            this.saveDebug = true;
            this.debugFile = debugFile.trim();
        }
        if (outputDir != null && !outputDir.isEmpty()) {
            this.outputDir = outputDir.trim();
        }

    }

    /**
     *
     * @return Robot Test File (plain Text)
     */
    public final String getRobotFile() {
        return robotFilePath;
    }

    /**
     * 
     * @return a list of Tags( separated by Comma ) which will be given to every
     *         testcases that will be invoked
     *         corresponds : -G --settag <tag>
     */
    public final String getSetTags() {
        return setTags;
    }

    @Exported
    public boolean issaveSummary() {
        return saveSummary;
    }

    @Exported
    public boolean issaveDebug() {
        return saveDebug;
    }

    /**
     *
     * @return a list of testcase-names which will be executed.
     *          corresponds: -t --test <name>
     */
    public final String getRunTestCasesByName() {
        return runTestCasesByName;
    }

    /**
     *
     * @return a list of tags of testcases which will be executed depending on that tags.
     *          corresponds: -i --include <tag>
     */
    public final String getRunTestCasesByTag() {
        return runTestCasesByTag;
    }

    /**
     *
     * @return a list of tags of testcases which will NOT be executed depending on that tags.
     *          corresponds: -e --exclude <tag>
     */
    public final String getExcludeTestCasesByTag() {
        return excludeTestCases;
    }

    /**
     *
     * @return a list of tags, which are noncritical
     *         (if they are FAIL, the test will still be passed).
     *          corresponds:-n --noncritical <tag>
     */
    public final String getNonCriticalTestCasesByTag() {
        return nonCriticalTestCases;
    }

    /**
     *
     * @return the the file to read variables from ( in PYTHON)
     *          see pybot --help for details
     */
    public final String getVariableFile() {
        return variableFile;
    }

    /**
     *
     * @return a String of Arguments which will be passed to the pybot command ( at the end),
     *         in case an option is needed which is not wrapped
     *          see pybot --help for details
     */
    public final String getArgumentFile() {
        return argumentFile;
    }

    /**
     *
     * @return the name of the summaryFile in case it will be created. If not, it
     *          returns null.
     *          corresponds: --summary=name
     *          see pybot --help for details
     */
    public final String getSummaryFile() {
        return summaryFile;
    }

    /**
     *
     * @return the name of the debugFile in case it will be created. If not, it
     *          returns null.
     *          corresponds: --debugfile=name
     *          see pybot --help for details
     */
    public final String getDebugFile() {
        return debugFile;
    }

    /**
     *
     * @return the Filepath in which the output will be created.
     *          see pybot --help for details
     */
    public final String getOutputDir() {
        return outputDir;
    }

    /**
     * performing Build-Step
     * Make changes here to copy the results to  a specific directory with a certain
     * convention in order to be able to see all results
     *
     * @param build
     * @param launcher
     * @param listener
     * @return
     * @throws InterruptedException
     */
    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, TaskListener listener) throws InterruptedException {
        boolean result = super.perform(build, launcher, listener);

        /**
         * All default output ( log.html, report.html, output.xml) will have the
         * default names, but the ${HUDSON_WORKSPACE}/outputDir as Result directory.
         *
         * A User could give a debug and a summaryfile a custom name with a relative
         * path, referring to the outputDir ( if it exists )
         *
         *So : RobotResultDir robotResult = build.getWorkspace();
         *      if(outputDir != null)robotResult = new FilePath(robotResult,outputDir);
         *      All files refer to this directory
         *
         * Safest way to get the real Output is to parse the ConsoleOutput.
         * In case of a custom workspace( fix directory), the outputdir of every
         * run will refer to the same file.
         * So if pybot do not start ( due to an failure or something else ),
         * it will not create the output. That means the old result could be
         * mistaken as the new one.
         * Use : getReportFromLog(build)
         *       getOutputFromLog(build)
         *       getLogFromLog(build)
         *       getSummaryFromLog(build)
         *       getDebugFromLog(build)
         * These functions work ONLY correct for one pybot-BuildStep per Job. ( see todo)
         */
        return result;
    }

    /**
     *
     * BUILD COMMAND with arguments
     *
     * @param script Filepath of tempfile that was created whit the content of getContents().
     *
     * @return The splitted command which will be executed
     */
    @Override
    protected String[] buildCommandLine(FilePath script) {
        return getCommandLine().toArray(new String[0]);
    }

    /**
     * Parse ConsoleOutput from pybot and return report-File ( in case it was
     * created) . If not return null.
     * @param build from which the report will be returned
     * @return
     */
    protected static File getReportFromLog(AbstractBuild<?, ?> build) {
        return getFromLog(build, reportFilePatternPybot);
    }

    /**
     * Parse ConsoleOutput from pybot and return output-File(xml)( in case it was
     * created) . If not return null.
     * @param build from which the output will be returned
     * @return
     */
    protected static File getOutputFromLog(AbstractBuild<?, ?> build) {
        return getFromLog(build, outputFilePatternPybot);
    }

    /**
     * Parse ConsoleOutput from pybot and return summary-File ( in case it was
     * created) . If not return null.
     * @param build from which the summary will be returned
     * @return
     */
    protected static File getSummaryFromLog(AbstractBuild<?, ?> build) {
        return getFromLog(build, summaryFilePatternPybot);
    }

    /**
     * Parse ConsoleOutput from pybot and return log-File ( in case it was
     * created) . If not return null.
     * @param build from which the log will be returned
     * @return
     */
    protected static File getLogFromLog(AbstractBuild<?, ?> build) {
        return getFromLog(build, logFilePatternPybot);
    }

    /**
     * Parse ConsoleOutput from pybot and return summary-File ( in case it was
     * created) . If not return null.
     * @param build from which the debug will be returned
     * @return
     */
    protected static File getDebugFromLog(AbstractBuild<?, ?> build) {
        return getFromLog(build, debugFilePatternPybot);
    }

    /**
     * return LATEST file of the ConsoleOutput, which line matches a certain pattern.
     * The argument pattern does not correspond to the actual pattern
     * ( see getFilePathFromLog)
     * @param build in which the pattern will be searched
     * @param pattern the variable part of the matched pattern
     * @return
     */
    private static File getFromLog(AbstractBuild<?, ?> build, String pattern) {
        if (build == null) {
            return null;
        }
        //get consoleOutput for this build
        File logFile = build.getLogFile().getAbsoluteFile();
        if (logFile != null && logFile.isFile()) {
            return getFilePathFromLog(logFile, pattern);
        }
        return null;
    }

    /**
     * Parse file and return a Path(File) which line matches a certain pattern.
     * @param file parsed File
     * @param customFilePattern corresponds to the variable part of the pattern,
     *        which every line of the consoleOutput will be matched
     * @return
     */
    private static File getFilePathFromLog(File file, String customFilePattern) {
        if (file == null || file == null) {
            return null;
        }
        BufferedReader br = null;
        String line = null;
        /**
         * Trigger which shows whether there is a pybot execution in the ConsoleOutput.
         */
        boolean pybot_execution = false;

        ArrayList<String> console = new ArrayList<String>();
        try {
            br = new BufferedReader(new FileReader(file));
            while ((line = br.readLine()) != null) {
                if (line.contains("pybot")) {
                    pybot_execution = true;
                }
                console.add(line);
            }

        } catch (IOException ex) {
            logger.log(Level.SEVERE, "IOException during reading of Hudson-logfile:"
                                     + file.getAbsolutePath(), ex);
            return null;
        }
        //quit if pybot was not called on the command line
        if (pybot_execution == false) {
            logger.log(Level.INFO, "pybot was not executed for Build with Log:{0}",
                       file.getAbsolutePath());
            return null;
        }

        String match = null;
        /**
         * the variable part of the pattern is the beginning of the line.
         * At the end of the execution of pybot, pybot prints the created output
         * and the absolute Path to that.
         * The Pattern pathPattern checks every kind of pattern ( see the definition
         * of this string)
         */
        String pattern = customFilePattern + pathPattern + ".+";
        for (int i = console.size() - 1; i >= 0; i--) {
            if (console.get(i).trim().matches(pattern)) {
                match = console.get(i);
                break;
            }
        }
        if (match == null) {
            return null;
        }
        String path = match.replaceAll(customFilePattern, "").trim();
        if (new File(path).isFile()) {
            return new File(path);
        }

        return null;

    }

    /**
     * @return A collection of the pybot command and its parameters
     *         Every argument but the last one will be quoted ( by the Launcher).
     *
     */
    private Collection<String> getCommandLine() {
        List<String> commandList = new ArrayList<String>();
        commandList.add("pybot");

        if (outputDir != null && !outputDir.isEmpty()) {
            commandList.add("--outputdir=" + outputDir);
        }
        if (saveSummary && summaryFile != null && !summaryFile.isEmpty()) {
            commandList.add("--summary=" + summaryFile);
        }
        if (saveDebug && summaryFile != null && !summaryFile.isEmpty()) {
            commandList.add("--debugfile=" + debugFile);
        }
        commandList.addAll(argToParamList(setTags, "--settag="));
        commandList.addAll(argToParamList(runTestCasesByName, "--test="));
        commandList.addAll(argToParamList(runTestCasesByTag, "--include="));
        commandList.addAll(argToParamList(excludeTestCases, "--exclude="));
        commandList.addAll(argToParamList(nonCriticalTestCases, "--noncritical="));
        commandList.addAll(argToParamList(variableFile, "--variablefile="));
        commandList.addAll(argToParamList(nonCriticalTestCases, "--noncritical="));

        if (argumentFile != null && !argumentFile.isEmpty()) {
            commandList.add("--argumentfile=" + argumentFile);
        }
        if (variableFile != null && !variableFile.isEmpty()) {
            commandList.add("--variablefile=" + variableFile);
        }

        commandList.add(robotFilePath);
        return commandList;

    }

    /**
     * Pybot accepts for most parameters a list of tokens(names/tags).
     * (e.g. --settag : tagX )
     * But every option does only accept one token. ( --settag=tag1 )
     * => So wrapping a several tokens as several arguments
     * ( --settag=tag1 --settag=tag2 )
     *
     * @param arg List of Tokens seperated by a comma.
     * @param argname Name of the Argument.
     * @return List of parameter to pass to the commandline interface
     */
    private Collection<String> argToParamList(String arg, String argname) {
        Collection<String> argList = new ArrayList<String>();
        if (argname == null || argname.isEmpty()
            || arg == null || arg.isEmpty()) {
            return argList;
        }

        for (String param : convertStringToList(arg)) {
            argList.add(argname + param);
        }
        return argList;

    }

    /**
     * Split comma-separated list into single tokens
     * @param string
     * @return
     */
    private Collection<String> convertStringToList(String string) {
        Collection<String> list = new ArrayList<String>();
        if (string == null || string.isEmpty()) {
            return list;
        }
        else if (!string.contains(",")) {
            list.add(string.trim());
            return list;
        }
        for (String token : string.split(",")) {
            if (!token.trim().isEmpty()) {
                list.add(token.trim());
            }
        }
        return list;
    }

    /**
     *
     * @return a String which will be saved in a tempfile
     */
    @Override
    protected String getContents() {
        return command;
    }

    /**
     * Nothing need to be copied -> pass nothing for Filename of the Tempfile
     * @return 
     */
    @Override
    protected String getFileExtension() {
        return "";
    }

    @Override
    public final DescriptorImpl getDescriptor() {
        return (DescriptorImpl) Hudson.getInstance().getDescriptor(getClass());
    }

    /**
     * override for correct handling in lists
     * @param obj
     * @return
     */
    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (obj instanceof RobotBuildStep) {
            RobotBuildStep testStep = (RobotBuildStep) obj;
            if (isEqual(this.robotFilePath, testStep.robotFilePath)
                && isEqual(this.setTags, testStep.setTags)
                && isEqual(this.runTestCasesByName, testStep.runTestCasesByName)
                && isEqual(this.runTestCasesByTag, testStep.runTestCasesByTag)
                && isEqual(this.excludeTestCases, testStep.excludeTestCases)
                && isEqual(this.nonCriticalTestCases, testStep.nonCriticalTestCases)
                && isEqual(this.variableFile, testStep.variableFile)
                && isEqual(this.argumentFile, testStep.argumentFile)
                && isEqual(this.outputDir, testStep.outputDir)
                && isEqual(this.summaryFile, testStep.summaryFile)
                && isEqual(this.debugFile, testStep.debugFile)) {
                return true;
            }
        }
        return false;
    }

    /**
     * override for correct handling in maps
     * @return
     */
    @Override
    public int hashCode() {
        int hash = 3;
        hash = 79 * hash + (this.robotFilePath != null ? this.robotFilePath.hashCode() : 0);
        hash = 79 * hash + (this.setTags != null ? this.setTags.hashCode() : 0);
        hash = 79 * hash + (this.runTestCasesByName != null ? this.runTestCasesByName.hashCode() : 0);
        hash = 79 * hash + (this.runTestCasesByTag != null ? this.runTestCasesByTag.hashCode() : 0);
        hash = 79 * hash + (this.excludeTestCases != null ? this.excludeTestCases.hashCode() : 0);
        hash = 79 * hash + (this.nonCriticalTestCases != null ? this.nonCriticalTestCases.hashCode() : 0);
        hash = 79 * hash + (this.variableFile != null ? this.variableFile.hashCode() : 0);
        hash = 79 * hash + (this.argumentFile != null ? this.argumentFile.hashCode() : 0);
        hash = 79 * hash + (this.outputDir != null ? this.outputDir.hashCode() : 0);
        hash = 79 * hash + (this.summaryFile != null ? this.summaryFile.hashCode() : 0);
        hash = 79 * hash + (this.debugFile != null ? this.debugFile.hashCode() : 0);
        return hash;
    }

    /**
     * test wheather two strings are equal, null strings are allowed.
     * used for equalsTo()
     * @param string1
     * @param string2
     * @return
     */
    private boolean isEqual(String string1, String string2) {
        return ((string1 == null && string2 == null) || (string1.equals(string2)));
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<Builder> {

        public DescriptorImpl() {
            super(RobotBuildStep.class);
        }

        @Override
        public String getDisplayName() {
            return "Execute Robot Framework(pybot)";
        }

        /**
         * Build a RobotBuildStep from the submitted Form and return it.
         * @param req
         * @param formData
         * @return
         */
        @Override
        public Builder newInstance(StaplerRequest req, JSONObject formData) {

            if (false) {
                /**
                 * Several attempts of try to get hudson to only accept one
                 * RobotBuildStep per job ( because severak BuildSteps would mean
                 * to adjust statistics to handle several and the getXXXFromLog
                 * to savely return a List of Files.
                 *
                 * Problem is that anything which is returned will either be thrown
                 * or is accepted.
                 * Hudson accepts null and  the same BuildStep ( two references
                 * of the same object and two equal objects ) several times as
                 * different Buildsteps.
                 * So how to get hudson to decline a BuildStep ?
                 *
                 * Should it be possible to do this or not ?
                 * Consultation with author (Janne Piironen) necessary
                 *
                 */
                RobotBuildStep thisStep = makeRobotBuildStep(formData);
                Object builders;
                try {
                    builders = Stapler.getCurrentRequest().getSubmittedForm().get("builder");
                } catch (ServletException ex) {
                    logger.log(Level.SEVERE, "Submitted Form does not contain builder, but this"
                                             + "Build-Step got invoked - return null");
                    return null;
                }
                if (builders instanceof JSONObject) {
                    return thisStep;
                }
                if (!(builders instanceof JSONArray)) {
                    return null;
                }
                JSONArray builderFormList = (JSONArray) builders;
                for(int i = 0;i< builderFormList.size();i++) {
                if(builderFormList.getJSONObject(i).containsKey("robotFile"))   {
                if(formData.equals(builderFormList.getJSONObject(i)))   {
                return thisStep;
                }
                else return null;
                }
                }
                //return thisStep;

                int count = 0;
                int firstRobotindex = -1;

                for (int i = 0; i < builderFormList.size(); i++) {
                if (builderFormList.getJSONObject(i).containsKey("robotFile")) {
                firstRobotindex = i;
                break;
                }
                }
                if(firstRobotindex == -1) return null;
                RobotBuildStep firstFormStep = makeRobotBuildStep(builderFormList.getJSONObject(firstRobotindex));
                if(firstFormStep.equals(thisStep)) return thisStep;
                //else return null;


                else {
                //int count = 0;
                //int firstRobotindex = -1;

                for (int i = 0; i < builderFormList.size(); i++) {
                if (builderFormList.getJSONObject(i).containsKey("robotFile")) {

                firstRobotindex = i;
                count++;
                if(count > 1)   {
                i = builderFormList.size();
                }
                }
                }

                String jobname = Stapler.getCurrentRequest().getParameter("name");
                List<Project> projects = Hudson.getInstance().getProjects();
                RobotBuildStep firstBuildStep = makeRobotBuildStep(builderFormList.getJSONObject(firstRobotindex));
                for( Project project : projects)    {
                if(jobname.trim().equals(project.getName().trim())) {
                if(!project.getBuilders().contains(firstBuildStep))return firstBuildStep;
                else    {
                List<Builder> builds = project.getBuilders();
                for(Builder build : builds) {
                if(firstBuildStep.equals(build))return build;
                }
                }

                break;
                }
                }
                return null;

                }
            }
            return makeRobotBuildStep(formData);
        }
    }

    /**
     * Extracts data for the execution of  a RobotBuildStep from the  formdata
     * @param formData
     * @return Robot Object
     */
    private static RobotBuildStep makeRobotBuildStep(JSONObject formData) {

        if (formData == null || !formData.containsKey("robotFile")) {
            return null;
        }
        String summaryFile = null;
        String debugFile = null;
        if (formData.containsKey("saveSummary")
            && formData.getJSONObject("saveSummary").containsKey("summaryFile")) {
            summaryFile =
            formData.getJSONObject("saveSummary").getString("summaryFile");
        }
        if (formData.containsKey("saveDebug")
            && formData.getJSONObject("saveDebug").containsKey("debugFile")) {
            debugFile = formData.getJSONObject("saveDebug").getString("debugFile");
        }
        return new RobotBuildStep(formData.getString("robotFile"),
                                  formData.getString("outputDir"),
                                  formData.getString("setTags"),
                                  formData.getString("runTestCasesByName"),
                                  formData.getString("runTestCasesByTag"),
                                  formData.getString("excludeTestCasesByTag"),
                                  formData.getString("nonCriticalTestCasesByTag"),
                                  formData.getString("variableFile"),
                                  formData.getString("argumentFile"),
                                  summaryFile, debugFile);
    }
}
