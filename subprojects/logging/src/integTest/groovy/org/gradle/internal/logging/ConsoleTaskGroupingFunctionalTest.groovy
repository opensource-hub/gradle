/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.logging

import org.gradle.integtests.fixtures.AbstractConsoleFunctionalSpec

import static org.gradle.util.TextUtil.normaliseFileSeparators

class ConsoleTaskGroupingFunctionalTest extends AbstractConsoleFunctionalSpec {

    private static final String JAVA_SRC_DIR_PATH = 'src/main/java'

    def setup() {
        executer.expectDeprecationWarning()
    }

    def "compiler warnings emitted from compilation task are grouped"() {
        given:
        def javaSourceFile = file("$JAVA_SRC_DIR_PATH/MyClass.java")

        buildFile << """
            apply plugin: 'java'

            compileJava {
                options.compilerArgs = ['-Xlint:all']
            }
        """

        file("$JAVA_SRC_DIR_PATH/Legacy.java") << """
            @Deprecated
            public class Legacy { }
        """

        file("$JAVA_SRC_DIR_PATH/MyClass.java") << """
            public class MyClass {
                public void instantiateDeprecatedClass() {
                    new Legacy();
                }
            }
        """

        when:
        succeeds('compileJava')

        then:
        normaliseFileSeparators(result.output).contains("""> Task :compileJava\u001B[m\u001B[0K
$javaSourceFile:4: warning: [deprecation] Legacy in unnamed package has been deprecated
                    new Legacy();
                        ^
1 warning""")
    }
}
