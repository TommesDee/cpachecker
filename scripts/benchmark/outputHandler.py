"""
CPAchecker is a tool for configurable software verification.
This file is part of CPAchecker.

Copyright (C) 2007-2013  Dirk Beyer
All rights reserved.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.


CPAchecker web page:
  http://cpachecker.sosy-lab.org
"""

# prepare for Python 3
from __future__ import absolute_import, print_function, unicode_literals

import threading
import time
import sys
import os
import xml.etree.ElementTree as ET

from . import filewriter
from . import result
from . import runexecutor
from . import util as Util

# colors for column status in terminal
USE_COLORS = True
COLOR_GREEN = "\033[32;1m{0}\033[m"
COLOR_RED = "\033[31;1m{0}\033[m"
COLOR_ORANGE = "\033[33;1m{0}\033[m"
COLOR_MAGENTA = "\033[35;1m{0}\033[m"
COLOR_DEFAULT = "{0}"
COLOR_DIC = {result.CATEGORY_CORRECT: COLOR_GREEN,
             result.CATEGORY_WRONG:   COLOR_RED,
             result.CATEGORY_UNKNOWN:   COLOR_ORANGE,
             result.CATEGORY_ERROR:     COLOR_MAGENTA,
             None: COLOR_DEFAULT}

LEN_OF_STATUS = 22

TERMINAL_TITLE=''
_term = os.environ.get('TERM', '')
if _term.startswith(('xterm', 'rxvt')):
    TERMINAL_TITLE = "\033]0;Benchmark {0}\007"
elif _term.startswith('screen'):
    TERMINAL_TITLE = "\033kBenchmark {0}\033\\"

# the number of digits after the decimal separator of the time column,
# for the other columns it can be configured in the xml-file
TIME_PRECISION = 2

MEMLIMIT = runexecutor.MEMLIMIT
TIMELIMIT = runexecutor.TIMELIMIT
CORELIMIT = runexecutor.CORELIMIT


class OutputHandler:
    """
    The class OutputHandler manages all outputs to the terminal and to files.
    """

    printLock = threading.Lock()

    def __init__(self, benchmark):
        """
        The constructor of OutputHandler collects information about the benchmark and the computer.
        """

        self.allCreatedFiles = []
        self.benchmark = benchmark
        self.statistics = Statistics()

        # get information about computer
        (opSystem, cpuModel, numberOfCores, maxFrequency, memory, hostname) = self.getSystemInfo()
        version = self.benchmark.toolVersion

        memlimit = None
        timelimit = None
        corelimit = None
        if MEMLIMIT in self.benchmark.rlimits:
            memlimit = str(self.benchmark.rlimits[MEMLIMIT]) + " MB"
        if TIMELIMIT in self.benchmark.rlimits:
            timelimit = str(self.benchmark.rlimits[TIMELIMIT]) + " s"
        if CORELIMIT in self.benchmark.rlimits:
            corelimit = str(self.benchmark.rlimits[CORELIMIT])

        self.storeHeaderInXML(version, memlimit, timelimit, corelimit, opSystem, cpuModel,
                              numberOfCores, maxFrequency, memory, hostname)
        self.writeHeaderToLog(version, memlimit, timelimit, corelimit, opSystem, cpuModel,
                              numberOfCores, maxFrequency, memory, hostname)

        self.XMLFileNames = []



    def storeSystemInfo(self, opSystem, cpuModel, numberOfCores, maxFrequency, memory, hostname):
        osElem = ET.Element("os", {"name":opSystem})
        cpuElem = ET.Element("cpu", {"model":cpuModel, "cores":numberOfCores, "frequency":maxFrequency})
        ramElem = ET.Element("ram", {"size":memory})
        systemInfo = ET.Element("systeminfo", {"hostname":hostname})
        systemInfo.append(osElem)
        systemInfo.append(cpuElem)
        systemInfo.append(ramElem)
        self.XMLHeader.append(systemInfo)


    def storeHeaderInXML(self, version, memlimit, timelimit, corelimit, opSystem,
                         cpuModel, numberOfCores, maxFrequency, memory, hostname):

        # store benchmarkInfo in XML
        self.XMLHeader = ET.Element("result",
                    {"benchmarkname": self.benchmark.name, "date": self.benchmark.dateISO,
                     "tool": self.benchmark.toolName, "version": version})

        self.XMLHeader.set(MEMLIMIT, memlimit if memlimit else '-')
        self.XMLHeader.set(TIMELIMIT, timelimit if timelimit else '-')
        self.XMLHeader.set(CORELIMIT, corelimit if corelimit else '-')

        if not self.benchmark.config.cloud:
            # store systemInfo in XML
            self.storeSystemInfo(opSystem, cpuModel, numberOfCores, maxFrequency, memory, hostname)

        # store columnTitles in XML
        columntitlesElem = ET.Element("columns")
        columntitlesElem.append(ET.Element("column", {"title": "status"}))
        columntitlesElem.append(ET.Element("column", {"title": "cputime"}))
        columntitlesElem.append(ET.Element("column", {"title": "walltime"}))
        for column in self.benchmark.columns:
            columnElem = ET.Element("column", {"title": column.title})
            columntitlesElem.append(columnElem)
        self.XMLHeader.append(columntitlesElem)

        # Build dummy entries for output, later replaced by the results,
        # The dummy XML elements are shared over all runs.
        self.XMLDummyElems = [ET.Element("column", {"title": "status", "value": ""}),
                      ET.Element("column", {"title": "cputime", "value": ""}),
                      ET.Element("column", {"title": "walltime", "value": ""})]
        for column in self.benchmark.columns:
            self.XMLDummyElems.append(ET.Element("column",
                        {"title": column.title, "value": ""}))


    def writeHeaderToLog(self, version, memlimit, timelimit, corelimit, opSystem,
                         cpuModel, numberOfCores, maxFrequency, memory, hostname):
        """
        This method writes information about benchmark and system into TXTFile.
        """

        columnWidth = 20
        simpleLine = "-" * (60) + "\n\n"

        header = "   BENCHMARK INFORMATION\n"\
                + "benchmark:".ljust(columnWidth) + self.benchmark.name + "\n"\
                + "date:".ljust(columnWidth) + self.benchmark.dateISO + "\n"\
                + "tool:".ljust(columnWidth) + self.benchmark.toolName\
                + " " + version + "\n"

        if memlimit:
            header += "memlimit:".ljust(columnWidth) + memlimit + "\n"
        if timelimit:
            header += "timelimit:".ljust(columnWidth) + timelimit + "\n"
        if corelimit:
            header += "CPU cores used:".ljust(columnWidth) + corelimit + "\n"
        header += simpleLine

        systemInfo = "   SYSTEM INFORMATION\n"\
                + "host:".ljust(columnWidth) + hostname + "\n"\
                + "os:".ljust(columnWidth) + opSystem + "\n"\
                + "cpu:".ljust(columnWidth) + cpuModel + "\n"\
                + "- cores:".ljust(columnWidth) + numberOfCores + "\n"\
                + "- max frequency:".ljust(columnWidth) + maxFrequency + "\n"\
                + "ram:".ljust(columnWidth) + memory + "\n"\
                + simpleLine

        self.description = header + systemInfo

        runSetName = None
        runSets = [runSet for runSet in self.benchmark.runSets if runSet.shouldBeExecuted()]
        if len(runSets) == 1:
            # in case there is only a single run set to to execute, we can use its name
            runSetName = runSets[0].name

        # write to file
        TXTFileName = self.getFileName(runSetName, "txt")
        self.TXTFile = filewriter.FileWriter(TXTFileName, self.description)
        self.allCreatedFiles.append(TXTFileName)


    def getSystemInfo(self):
        """
        This function returns some information about the computer.
        """

        # get info about OS
        (sysname, name, kernel, version, machine) = os.uname()
        opSystem = sysname + " " + kernel + " " + machine

        # get info about CPU
        cpuInfo = dict()
        maxFrequency = 'unknown'
        cpuInfoFilename = '/proc/cpuinfo'
        numberOfCores = 'unknown'
        if os.path.isfile(cpuInfoFilename) and os.access(cpuInfoFilename, os.R_OK):
            cpuInfoFile = open(cpuInfoFilename, 'rt')
            cpuInfoLines = [tuple(line.split(':')) for line in
                            cpuInfoFile.read()
                                       .replace('\n\n', '\n').replace('\t', '')
                                       .strip('\n').split('\n')]
            cpuInfo = dict(cpuInfoLines)
            cpuInfoFile.close()
            numberOfCores = str(len([line for line in cpuInfoLines if line[0] == 'processor']))
        cpuModel = cpuInfo.get('model name', 'unknown') \
                          .strip() \
                          .replace("(R)", "") \
                          .replace("(TM)", "") \
                          .replace("(tm)", "")
        if 'cpu MHz' in cpuInfo:
            maxFrequency = cpuInfo['cpu MHz'].split('.')[0].strip() + ' MHz'

        # modern cpus may not work with full speed the whole day
        # read the number from cpufreq and overwrite maxFrequency from above
        freqInfoFilename = '/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq'
        if os.path.isfile(freqInfoFilename) and os.access(freqInfoFilename, os.R_OK):
            frequencyInfoFile = open(freqInfoFilename, 'rt')
            maxFrequency = frequencyInfoFile.read().strip('\n')
            frequencyInfoFile.close()
            maxFrequency = str(int(maxFrequency) // 1000) + ' MHz'

        # get info about memory
        memInfo = dict()
        memInfoFilename = '/proc/meminfo'
        if os.path.isfile(memInfoFilename) and os.access(memInfoFilename, os.R_OK):
            memInfoFile = open(memInfoFilename, 'rt')
            memInfo = dict(tuple(str.split(': ')) for str in
                            memInfoFile.read()
                            .replace('\t', '')
                            .strip('\n').split('\n'))
            memInfoFile.close()
        memTotal = memInfo.get('MemTotal', 'unknown').strip()

        return (opSystem, cpuModel, numberOfCores, maxFrequency, memTotal, name)


    def outputBeforeRunSet(self, runSet):
        """
        The method outputBeforeRunSet() calculates the length of the
        first column for the output in terminal and stores information
        about the runSet in XML.
        @param runSet: current run set
        """

        self.runSet = runSet

        sourcefiles = [run.sourcefile for run in runSet.runs]

        # common prefix of file names
        self.commonPrefix = os.path.commonprefix(sourcefiles) # maybe with parts of filename
        self.commonPrefix = self.commonPrefix[: self.commonPrefix.rfind('/') + 1] # only foldername

        # length of the first column in terminal
        self.maxLengthOfFileName = max(len(file) for file in sourcefiles) if sourcefiles else 20
        self.maxLengthOfFileName = max(20, self.maxLengthOfFileName - len(self.commonPrefix))

        # write run set name to terminal
        numberOfFiles = len(runSet.runs)
        numberOfFilesStr = ("     (1 file)" if numberOfFiles == 1
                        else "     ({0} files)".format(numberOfFiles))
        Util.printOut("\nexecuting run set"
            + (" '" + runSet.name + "'" if runSet.name else "")
            + numberOfFilesStr
            + (TERMINAL_TITLE.format(runSet.fullName) if USE_COLORS and sys.stdout.isatty() else ""))

        # write information about the run set into TXTFile
        self.writeRunSetInfoToLog(runSet)

        # prepare information for text output
        for run in runSet.runs:
            run.resultline = self.formatSourceFileName(run.sourcefile)

        # prepare XML structure for each run and runSet
            run.xml = ET.Element("sourcefile", {"name": run.sourcefile})
            if run.specificOptions:
                run.xml.set("options", " ".join(run.specificOptions))
            run.xml.extend(self.XMLDummyElems)

        runSet.xml = self.runsToXML(runSet, runSet.runs)

        # write (empty) results to TXTFile and XML
        self.TXTFile.append(self.runSetToTXT(runSet), False)
        XMLFileName = self.getFileName(runSet.name, "xml")
        self.XMLFile = filewriter.FileWriter(XMLFileName,
                       Util.XMLtoString(runSet.xml))
        self.XMLFile.lastModifiedTime = time.time()
        self.allCreatedFiles.append(XMLFileName)
        self.XMLFileNames.append(XMLFileName)


    def outputForSkippingRunSet(self, runSet, reason=None):
        '''
        This function writes a simple message to terminal and logfile,
        when a run set is skipped.
        There is no message about skipping a run set in the xml-file.
        '''

        # print to terminal
        Util.printOut("\nSkipping run set" +
               (" '" + runSet.name + "'" if runSet.name else "") +
               (" " + reason if reason else "")
              )

        # write into TXTFile
        runSetInfo = "\n\n"
        if runSet.name:
            runSetInfo += runSet.name + "\n"
        runSetInfo += "Run set {0} of {1}: skipped {2}\n".format(
                runSet.index, len(self.benchmark.runSets), reason or "")
        self.TXTFile.append(runSetInfo)


    def writeRunSetInfoToLog(self, runSet):
        """
        This method writes the information about a run set into the TXTFile.
        """

        runSetInfo = "\n\n"
        if runSet.name:
            runSetInfo += runSet.name + "\n"
        runSetInfo += "Run set {0} of {1} with options: {2}\n\n".format(
                runSet.index, len(self.benchmark.runSets),
                " ".join(runSet.options))

        titleLine = self.createOutputLine("sourcefile", "status", "cpu time",
                            "wall time", self.benchmark.columns, True)

        runSet.simpleLine = "-" * (len(titleLine))

        runSetInfo += titleLine + "\n" + runSet.simpleLine + "\n"

        # write into TXTFile
        self.TXTFile.append(runSetInfo)


    def outputBeforeRun(self, run):
        """
        The method outputBeforeRun() prints the name of a file to terminal.
        It returns the name of the logfile.
        @param run: a Run object
        """
        # output in terminal
        try:
            OutputHandler.printLock.acquire()

            timeStr = time.strftime("%H:%M:%S", time.localtime()) + "   "
            progressIndicator = " ({0}/{1})".format(self.runSet.runs.index(run), len(self.runSet.runs))
            terminalTitle = TERMINAL_TITLE.format(self.runSet.fullName + progressIndicator) if USE_COLORS and sys.stdout.isatty() else ""
            if self.benchmark.numOfThreads == 1:
                Util.printOut(terminalTitle + timeStr + self.formatSourceFileName(run.sourcefile), '')
            else:
                Util.printOut(terminalTitle + timeStr + "starting   " + self.formatSourceFileName(run.sourcefile))
        finally:
            OutputHandler.printLock.release()

        # get name of file-specific log-file
        self.allCreatedFiles.append(run.logFile)


    def outputAfterRun(self, run):
        """
        The method outputAfterRun() prints filename, result, time and status
        of a run to terminal and stores all data in XML
        """

        # format times, type is changed from float to string!
        cpuTimeStr = Util.formatNumber(run.cpuTime, TIME_PRECISION)
        wallTimeStr = Util.formatNumber(run.wallTime, TIME_PRECISION)

        # format numbers, numberOfDigits is optional, so it can be None
        for column in run.columns:
            if column.numberOfDigits is not None:

                # if the number ends with "s" or another letter, remove it
                if (not column.value.isdigit()) and column.value[-2:-1].isdigit():
                    column.value = column.value[:-1]

                try:
                    floatValue = float(column.value)
                    column.value = Util.formatNumber(floatValue, column.numberOfDigits)
                except ValueError: # if value is no float, don't format it
                    pass

        # store information in run
        run.resultline = self.createOutputLine(run.sourcefile, run.status,
                cpuTimeStr, wallTimeStr, run.columns)
        self.addValuesToRunXML(run, cpuTimeStr, wallTimeStr)

        # output in terminal/console
        if USE_COLORS and sys.stdout.isatty(): # is terminal, not file
            statusStr = COLOR_DIC[run.category].format(run.status.ljust(LEN_OF_STATUS))
        else:
            statusStr = run.status.ljust(LEN_OF_STATUS)

        try:
            OutputHandler.printLock.acquire()

            valueStr = statusStr + cpuTimeStr.rjust(8) + wallTimeStr.rjust(8)
            if self.benchmark.numOfThreads == 1:
                Util.printOut(valueStr)
            else:
                timeStr = time.strftime("%H:%M:%S", time.localtime()) + " "*14
                Util.printOut(timeStr + self.formatSourceFileName(run.sourcefile) + valueStr)

            # write result in TXTFile and XML
            self.TXTFile.append(self.runSetToTXT(run.runSet), False)
            self.statistics.addResult(run.category, run.status)

            # we don't want to write this file to often, it can slow down the whole script,
            # so we wait at least 10 seconds between two write-actions
            currentTime = time.time()
            if currentTime - self.XMLFile.lastModifiedTime > 10:
                self.XMLFile.replace(Util.XMLtoString(run.runSet.xml))
                self.XMLFile.lastModifiedTime = currentTime

        finally:
            OutputHandler.printLock.release()


    def outputAfterRunSet(self, runSet, cpuTime, wallTime):
        """
        The method outputAfterRunSet() stores the times of a run set in XML.
        @params cpuTime, wallTime: accumulated times of the run set
        """

        # write results to files
        self.XMLFile.replace(Util.XMLtoString(runSet.xml))

        if len(runSet.blocks) > 1:
            for block in runSet.blocks:
                blockFileName = self.getFileName(runSet.name, block.name + ".xml")
                filewriter.writeFile(
                    Util.XMLtoString(self.runsToXML(runSet, block.runs, block.name)),
                    blockFileName
                )
                self.allCreatedFiles.append(blockFileName)

        self.TXTFile.append(self.runSetToTXT(runSet, True, cpuTime, wallTime))


    def runSetToTXT(self, runSet, finished=False, cpuTime=0, wallTime=0):
        lines = []

        # store values of each run
        for run in runSet.runs: lines.append(run.resultline)

        lines.append(runSet.simpleLine)

        # write endline into TXTFile
        if finished:
            endline = ("Run set {0}".format(runSet.index))

            # format time, type is changed from float to string!
            if(cpuTime == None):
                cpuTimeStr = str(cpuTime)
            else:
                cpuTimeStr = Util.formatNumber(cpuTime, TIME_PRECISION)
            if(wallTime == None):
                wallTimeStr = str(wallTime)
            else:
                wallTimeStr = Util.formatNumber(wallTime, TIME_PRECISION)

            lines.append(self.createOutputLine(endline, "done", cpuTimeStr,
                             wallTimeStr, []))

        return "\n".join(lines) + "\n"

    def runsToXML(self, runSet, runs, blockname=None):
        """
        This function creates the XML structure for a list of runs
        """
        # copy benchmarkinfo, limits, columntitles, systeminfo from XMLHeader
        runsElem = Util.getCopyOfXMLElem(self.XMLHeader)
        runsElem.set("options", " ".join(runSet.options))
        if blockname is not None:
            runsElem.set("block", blockname)
            runsElem.set("name", ((runSet.realName + ".") if runSet.realName else "") + blockname)
        elif runSet.realName:
            runsElem.set("name", runSet.realName)

        # collect XMLelements from all runs
        for run in runs: runsElem.append(run.xml)

        return runsElem


    def addValuesToRunXML(self, run, cpuTimeStr, wallTimeStr):
        """
        This function adds the result values to the XML representation of a run.
        """
        runElem = run.xml
        for elem in list(runElem):
            runElem.remove(elem)
        runElem.append(ET.Element("column", {"title": "status", "value": run.status}))
        runElem.append(ET.Element("column", {"title": "category", "value": run.category}))
        runElem.append(ET.Element("column", {"title": "cputime", "value": cpuTimeStr}))
        runElem.append(ET.Element("column", {"title": "walltime", "value": wallTimeStr}))
        if run.memUsage is not None:
            runElem.append(ET.Element("column", {"title": "memUsage", "value": str(run.memUsage)}))
        if run.host:
            runElem.append(ET.Element("column", {"title": "host", "value": run.host}))

        for column in run.columns:
            runElem.append(ET.Element("column",
                        {"title": column.title, "value": column.value}))


    def createOutputLine(self, sourcefile, status, cpuTimeDelta, wallTimeDelta, columns, isFirstLine=False):
        """
        @param sourcefile: title of a sourcefile
        @param status: status of programm
        @param cpuTimeDelta: time from running the programm
        @param wallTimeDelta: time from running the programm
        @param columns: list of columns with a title or a value
        @param isFirstLine: boolean for different output of headline and other lines
        @return: a line for the outputFile
        """

        lengthOfTime = 11
        minLengthOfColumns = 8

        outputLine = self.formatSourceFileName(sourcefile) + \
                     status.ljust(LEN_OF_STATUS) + \
                     cpuTimeDelta.rjust(lengthOfTime) + \
                     wallTimeDelta.rjust(lengthOfTime)

        for column in columns:
            columnLength = max(minLengthOfColumns, len(column.title)) + 2

            if isFirstLine:
                value = column.title
            else:
                value = column.value

            outputLine = outputLine + str(value).rjust(columnLength)

        return outputLine


    def outputAfterBenchmark(self, isStoppedByInterrupt):
        self.statistics.printToTerminal()

        if self.XMLFileNames:
            tableGeneratorPath = os.path.join(os.path.dirname(__file__), os.path.pardir, 'table-generator.py')
            Util.printOut("In order to get HTML and CSV tables, run\n{0} '{1}'"
                          .format(os.path.relpath(tableGeneratorPath, '.'),
                                  "' '".join(self.XMLFileNames)))

        if isStoppedByInterrupt:
            Util.printOut("\nScript was interrupted by user, some runs may not be done.\n")


    def getFileName(self, runSetName, fileExtension):
        '''
        This function returns the name of the file for a run set
        with an extension ("txt", "xml").
        '''

        fileName = self.benchmark.outputBase + ".results."

        if runSetName:
            fileName += runSetName + "."

        return fileName + fileExtension


    def formatSourceFileName(self, fileName):
        '''
        Formats the file name of a program for printing on console.
        '''
        fileName = fileName.replace(self.commonPrefix, '', 1)
        return fileName.ljust(self.maxLengthOfFileName + 4)


class Statistics:

    def __init__(self):
        self.dic = dict((category,0) for category in COLOR_DIC)
        self.dic[(result.CATEGORY_WRONG, result.STR_TRUE)] = 0
        self.counter = 0

    def addResult(self, category, status):
        self.counter += 1
        assert category in self.dic
        if category == result.CATEGORY_WRONG and status == result.STR_TRUE:
            self.dic[(result.CATEGORY_WRONG, result.STR_TRUE)] += 1
        self.dic[category] += 1


    def printToTerminal(self):
        Util.printOut('\n'.join(['\nStatistics:' + str(self.counter).rjust(13) + ' Files',
                 '    correct:        ' + str(self.dic[result.CATEGORY_CORRECT]).rjust(4),
                 '    unknown:        ' + str(self.dic[result.CATEGORY_UNKNOWN] + self.dic[result.CATEGORY_ERROR]).rjust(4),
                 '    false positives:' + str(self.dic[result.CATEGORY_WRONG] - self.dic[(result.CATEGORY_WRONG, result.STR_TRUE)]).rjust(4) + \
                 '        (result is false, file is true or has a different false-property)',
                 '    false negatives:' + str(self.dic[(result.CATEGORY_WRONG, result.STR_TRUE)]).rjust(4) + \
                 '        (result is true, file is false)',
                 '']))