// **********************************************************************
//
// Copyright (c) 2014-2016 ZeroC, Inc. All rights reserved.
//
// **********************************************************************

package com.zeroc.gradle.icebuilder.slice;

import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.GradleException

class SliceExtension {

    final NamedDomainObjectContainer<Java> java;

    private def iceHome = null
    private def iceVersion = null
    private def srcDist = false
    private def freezeHome = null
    private def sliceDir = null
    private def slice2java = null
    private def slice2freezej = null
    private def jarDir = null
    private def cppPlatform = null
    private def cppConfiguration = null

    private def env = []
    def output

    private static Configuration configuration = null

    class Configuration {

        def _iceHome = null
        def _iceVersion = null
        def _srcDist = false
        def _freezeHome = null
        def _sliceDir = null
        def _slice2java = null
        def _slice2freezej = null
        def _jarDir = null
        def _cppPlatform = null
        def _cppConfiguration = null
        def _env = []

        Configuration(iceHome = null) {
            _iceHome = iceHome ? iceHome : getIceHome();
            _freezeHome = freezeHome

            // Guess the cpp platform and cpp configuration to use with Windows source builds
            _cppConfiguration = cppConfiguration ? cppConfiguration : System.getenv("CPP_CONFIGURATION")
            _cppPlatform = cppPlatform ? cppPlatform : System.getenv("CPP_PLATFORM")

            def os = System.properties['os.name']

            if(_iceHome != null) {
                _srcDist = new File([_iceHome, "java", "build.gradle"].join(File.separator)).exists()
                _slice2java = getSlice2java(_iceHome)

                //
                // If freezeHome is not set we assume slice2freezej resides in the same location as slice2java
                // otherwise slice2freezej will be located in the freeze home bin directory.
                //
                _slice2freezej = getSlice2freezej(freezeHome ? freezeHome : iceHome)

                //
                // Setup the environment required to run slice2java/slice2freezej commands
                //
                if(os.contains("Linux")) {
                    def cppDir = _srcDist ? "${_iceHome}/cpp" : _iceHome;

                    def libdir = new File("${cppDir}/lib/i386-linux-gnu").exists() ?
                        "${cppDir}/lib/i386-linux-gnu" : "${cppDir}/lib"
                    def lib64dir = new File("${cppDir}/lib/x86_64-linux-gnu").exists() ?
                        "${cppDir}/lib/x86_64-linux-gnu" : "${cppDir}/lib64"
                    def env = [libdir, lib64dir]
                    if(System.env.LD_LIBRARY_PATH) {
                        env.add(System.env.LD_LIBRARY_PATH)
                    }
                    _env = ["LD_LIBRARY_PATH=${env.join(File.pathSeparator)}"]
                }

                //
                // Retrieve the version of the Ice distribution being used
                //
                _iceVersion = getIceVersion(_iceHome)

                //
                // Guess the slice and jar directories of the Ice distribution we are using
                //
                if(_iceHome in ["/usr", "/usr/local"]) {
                    _sliceDir = [_iceHome, "share", "Ice-${_iceVersion}", "slice"].join(File.separator)
                    _jarDir = [_iceHome, "share", "java"].join(File.separator)
                } else {
                    _sliceDir = [_iceHome, "slice"].join(File.separator)
                    _jarDir = _srcDist ?
                        [_iceHome, "java", "lib"].join(File.separator) :
                        [_iceHome, "lib"].join(File.separator)
                }
            }
        }

        def getIceHome() {
            if(System.env.ICE_HOME != null) {
                return System.env.ICE_HOME
            }

            def os = System.properties['os.name']
            if (os == "Mac OS X") {
                return "/usr/local"
            } else if (os.contains("Windows")) {
                return getWin32IceHome()
            } else {
                return "/usr"
            }
        }

        //
        // Query Win32 registry key and return the InstallDir value for the given key
        //
        def getWin32InstallDir(key) {
            def sout = new StringBuffer()
            def serr = new StringBuffer()
            def p = ["reg", "query", key, "/v", "InstallDir"].execute()
            p.waitForProcessOutput(sout, serr)
            if (p.exitValue() != 0) {
                return null
            }
            return sout.toString().split("    ")[3].trim()
        }

        //
        // Query Win32 registry and return the path of the latest Ice version available.
        //
        def getWin32IceHome() {
            def sout = new StringBuffer()
            def serr = new StringBuffer()

            def p = ["reg", "query", "HKLM\\Software\\ZeroC"].execute()
            p.waitForProcessOutput(sout, serr)
            if (p.exitValue() != 0) {
                //
                // reg query will fail if Ice is not installed
                //
                return ""
            }

            def iceInstallDir = null
            def iceVersion = null

            sout.toString().split("\\r?\\n").each {
                if (it.indexOf("HKEY_LOCAL_MACHINE\\Software\\ZeroC\\Ice") != -1) {
                    def installDir = getWin32InstallDir(it)
                    if (installDir != null) {
                        def version = getIceVersion(installDir).split("\\.")
                        if (version.length == 3) {
                            //
                            // Check if version is greater than current version
                            //
                            if (iceVersion == null || version[0] > iceVersion[0] ||
                                (version[0] == iceVersion[0] && version[1] > iceVersion[1]) ||
                                (version[0] == iceVersion[0] && version[1] == iceVersion[1] &&
                                 version[2] > iceVersion[2])) {
                                iceInstallDir = installDir
                                iceVersion = version
                            }
                        }
                    }
                }
            }
            return iceInstallDir
        }

        def getIceVersion(iceHome) {
            def slice2java = getSlice2java(iceHome)
            if(new File(slice2java).exists()) {
                def command = [slice2java, "--version"]
                def sout = new StringBuffer()
                def serr = new StringBuffer()
                def p = command.execute(_env, null)
                p.waitForProcessOutput(sout, serr)
                if (p.exitValue() != 0) {
                    println serr.toString()
                    throw new GradleException("${command[0]} command failed: ${p.exitValue()}")
                }
                return serr.toString().trim()
            } else {
                return ""
            }
        }

        def getSlice2java(iceHome) {
            return getSliceCompiler("slice2java", iceHome)
        }

        def getSlice2freezej(freezeHome) {
            return getSliceCompiler("slice2freezej", freezeHome)
        }

        //
        // Return the path to the specified slice compiler (slice2java|slice2freezej) with respect to
        // the specified homeDir (iceHome|freezeHome)
        //
        def getSliceCompiler(compilerName, homeDir) {
            def os = System.properties['os.name']
            //
            // Check if we are using a Slice source distribution
            //
            def srcDist = new File([homeDir, "java", "build.gradle"].join(File.separator)).exists()
            def sliceCompiler = null
            //
            // Set the location of the sliceCompiler executable
            //
            if (os.contains("Windows")) {
                if (srcDist) {
                    //
                    // Ice >= 3.7 Windows source distribution, the compiler is located in the platform
                    // configuration depend directory. Otherwise cppPlatform and cppConfiguration will be null and
                    // it will fallback to the common bin directory used with Ice < 3.7.
                    //
                    if (_cppPlatform != null && _cppConfiguration != null) {
                        sliceCompiler = [homeDir, "cpp", "bin", _cppPlatform, _cppConfiguration, "${compilerName}.exe"].join(File.separator)
                    }
                } else {
                    //
                    // With Ice >= 3.7 Windows binary distribution we use the compiler Win32/Release
                    // bin directory. We assume that if the file exists at this location we are using Ice >= 3.7
                    // distribution otherwise it will fallback to the common bin directory used with Ice < 3.7.
                    //
                    def path = [homeDir, "build", "native", "bin", "Win32", "Release", "${compilerName}.exe"].join(File.separator)
                    if (new File(path).exists()) {
                        sliceCompiler = path
                    }
                }
            }

            if (sliceCompiler == null) {
                sliceCompiler = srcDist ?
                    [homeDir, "cpp", "bin", compilerName].join(File.separator) :
                    [homeDir, "bin", compilerName].join(File.separator)
            }

            return sliceCompiler
        }
    }

    SliceExtension(java) {
        this.java = java
        init()
    }

    def java(Closure closure) {
        try {
            java.configure(closure)
        } catch(MissingPropertyException ex) {
            java.create('default', closure)
        }
    }

    private void init() {
        Configuration c = null
        if (iceHome) {
            c = new Configuration(iceHome)
        } else {
            if (configuration == null) {
                configuration = new Configuration()
            }
            c = configuration
        }

        iceHome = c._iceHome

        iceVersion = c._iceVersion
        srcDist = c._srcDist
        freezeHome = c._freezeHome
        sliceDir = c._sliceDir
        slice2java = c._slice2java
        slice2freezej = c._slice2freezej
        jarDir = c._jarDir
        cppPlatform = c._cppPlatform
        cppConfiguration = c._cppConfiguration
        env = c._env
    }

    def getIceHome() {
        return iceHome
    }

    def setIceHome(value) {
        iceHome = value
        init()
    }

    def getIceVersion() {
        return iceVersion
    }

    def getSrcDist() {
        return srcDist
    }

    def getFreezeHome() {
        return freezeHome
    }

    def setFreezeHome(value) {
        freezeHome = value
        init()
    }

    def getSliceDir() {
        return sliceDir
    }

    def getSlice2java() {
        return slice2java
    }

    def getSlice2freezej() {
        return slice2freezej
    }

    def getJarDir() {
        return jarDir
    }

    def getCppPlatform() {
        return cppPlatform
    }

    def setCppPlatform(value) {
        cppPlatform = value
        init()
    }

    def getCppConfiguration() {
        return cppConfiguration
    }

    def setCppConfiguration(value) {
        cppConfiguration = value
        init()
    }

    def getEnv() {
        return env
    }
}
