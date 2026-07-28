#!/usr/bin/env bash
set -euo pipefail
#
# Run .bsh file instrumented and with debug support agent on classpath
#
# BeanShell script file (`.bsh`) is read from STDIN.
# Instrumentation is done by `bshInstrumenter.main.kts`.
# The agent class is `cz.loplex.intellij.bsh.debug.agent.BshDebugAgent`
# and is used from this project build output (`build/`).
# The BeanShell interpreter is provided by Maven and automatically downloaded if needed.
#
# The instrumented script calls `BshDebugAgent.step(line, this.namespace)`
# before every statement.
# The agent connects to an IDE debug server only when the `bsh.debug.port`
# system property is set; otherwise it disables itself and the script just runs normally.
# The property value is set by this script and follows
# the [PORT] value passed as the command line argument.
#
# For an end-to-end try-out, start the stand-in IDE in one terminal:
#     ./tools/mock-ide.py 47784
# then run the script wired to that port in another:
#     ./tools/run-debug-bsh.sh 47784 < samples/showcase.bsh
#
# Usage:
#     ./tools/run-debug-bsh.sh [PORT] < script.bsh

# Uses ${@@Q} and empty-array expansion under `set -u`, both bash 4.4+ (2016).
if (( BASH_VERSINFO[0] < 4 || (BASH_VERSINFO[0] == 4 && BASH_VERSINFO[1] < 4) )); then
    echo "requires bash 4.4+ (found ${BASH_VERSION})" >&2
    exit 1
fi


### SET CONFIGURATION VARIABLES ###

# Set base directories
here=$( cd "$(dirname "${BASH_SOURCE[0]}")" && pwd )
repo=$( git rev-parse --show-toplevel )

# The BeanShell interpreter `bsh.jar` (bsh.Interpreter), pulled from Maven
declare -a bshLibMvnCoordinates=( 'org.apache-extras.beanshell' 'bsh' '2.0b6' )
#declare -a bshLibMvnCoordinates=( 'org.apache-extras.beanshell' 'bsh' '2.0b5' )
#declare -a bshLibMvnCoordinates=( 'org.beanshell'               'bsh' '2.0b5' )
#declare -a bshLibMvnCoordinates=( 'org.beanshell'               'bsh' '2.0b4' )
#declare -a bshLibMvnCoordinates=( 'bsh'                         'bsh' '2.0b1' )

# BshDebugAgent, compiled into `plugin/build/` by `./gradlew :plugin:compileJava`.
declare agent_classes_dir="${repo}/plugin/build/classes/java/main"
declare agent_class='cz.loplex.intellij.bsh.debug.agent.BshDebugAgent'


### UTIL FUNCTIONS ###

function dbgExec() {
  echo '+' "${@@Q}" >&2; "$@"
}

function mvnDep() {
  local groupId=$1 artifactId=$2 version=$3
  local jar_file="${HOME}/.m2/repository/${groupId//"."/"/"}/${artifactId}/${version}/${artifactId}-${version}.jar"
  [[ -f "${jar_file}" ]] || dbgExec mvn -q dependency:get -DgroupId="${groupId}" -DartifactId="${artifactId}" -Dversion="${version}" 1>&2
  [[ -f "${jar_file}" ]] && echo "${jar_file}"
}


### PERFORM PRE-EXECUTION CHECKS ###

# Check BshDebugAgent class is built.
if [[ ! -f "${agent_classes_dir}/${agent_class//"."/"/"}.class" ]]; then
    echo 'BshDebugAgent not compiled — run: ./gradlew compileJava' >&2
    exit 1
fi

# Ensure BeanShell interpreter library JAR downloaded and store it's path.
if ! bsh_jar=$( mvnDep "${bshLibMvnCoordinates[@]}" ); then
    echo "Error: 'bsh-2.0b6.jar' not available nor downloadable" >&2
    exit 1
fi

# Nothing is piped in? The instrumenter would silently block on the terminal.
if [[ -t 0 ]]; then
    echo '!!! no .bsh piped on stdin — reading from the terminal (Ctrl-D to end); usual form: run-debug-bsh.sh [PORT] < script.bsh' >&2
fi

# Get port number from argument and put it into system property.
declare -a portArgs=( ${1:+"-Dbsh.debug.port=$1"} )
if (( ${#portArgs[@]} == 0 )); then
  echo 'Warning: port was not provided - agent will not connect to IDE!' >&2
fi


### INSTRUMENT AND RUN ###

# Create temporary file where to put the instrumented script, kept after the run for possible inspection.
instrumented=$( mktemp --suffix='.bsh' )
runner_dir=$( dirname "${instrumented}" )

# Instrument input script on STDIN and writes the enriched script to temporary file.
dbgExec "${here}/bshInstrumenter.main.kts" > "${instrumented}"
echo "Instrumented script: ${instrumented}" >&2

# bsh.Interpreter's own main() catches a failing script's EvalError/TargetError, prints it and
# returns -- it never exits non-zero, so a caller (CI included) cannot tell success from failure.
# BshRunner makes the same source() call main() does but turns that same exception into exit(1).
cat > "${runner_dir}/BshRunner.java" <<'EOF'
import bsh.EvalError;
import bsh.Interpreter;
import bsh.TargetError;

public final class BshRunner {
    public static void main(String[] args) throws Exception {
        Interpreter interpreter = new Interpreter();
        try {
            interpreter.source(args[0], interpreter.getNameSpace());
        } catch (TargetError e) {
            System.err.println("Script threw exception: " + e);
            System.exit(1);
        } catch (EvalError e) {
            System.err.println("Evaluation Error: " + e);
            System.exit(1);
        }
    }
}
EOF
dbgExec javac -cp "${bsh_jar}" -d "${runner_dir}" "${runner_dir}/BshRunner.java"

# Run instrumented script in BeanShell interpreter with BshDebugAgent on classpath.
dbgExec exec java -cp "${agent_classes_dir}:${bsh_jar}:${runner_dir}" "${portArgs[@]}" 'BshRunner' "${instrumented}"
