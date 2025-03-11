# one-billion-row-challenge


# Processing and analysis of meteorological data

The task is to implement a multi-threaded system for processing large text files (with the extension .txt and .csv) in a given directory. The system should monitor changes in that directory, process files containing data about meteorological stations and recorded temperatures, and allow the user to launch additional tasks via the command line and check the status of execution of jobs.

The data in the files are organized according to the following format (each line represents one measurement):

`Hamburg;12.0`

`Bulawayo;8.9`

`Palembang;38.8`

`St. John's;15.2`

`Cracow;12.6`

`Bridgetown;26.9`

`Istanbul;6.2`

`Roseau;34.4`

`Conakry;31.2`

`Istanbul;23.0`

## 1 System components

### 1.1 Thread for directory monitoring

It listens to a given directory (defined via command line or configuration file) and detects new or changed files with the extension .txt and .csv.

The "last modified" value is recorded for each file - if the file has already been processed (the previous and current values ​​are the same), a new job is not started.
Reading of files is done part by part (not placing the whole file in memory) due to the potentially huge size of the files (measurements\_big.txt is \~ 14GB). 

When a change in the directory is detected, it is necessary to write a message about which files have been changed and/or added.


### 1.2 File processing and in-memory map updating

When a change is detected (and first run) a job is started (via ExecutorService) that processes all the files inside the directory. Each thread inside the ExecutorService should work on a single file. It is recommended to use 4 threads within the service.

Each line in the file contains the name of the meteorological station and the recorded temperature. The data is combined in an in-memory map organized alphabetically, where for each letter (the first letter of the station name) is stored: the number of stations starting with that letter and the sum of all measurements for those stations.
This file processing task that updates the map must be protected by synchronization mechanisms so that it does not collide with other read operations on the same files.
Note: In case of CSV file processing, it is necessary to skip the header.
Note: It is assumed that the contents of all files inside the directory will be in the correct format.

### 1.3 CLI thread and command handling

The user enters commands via the command line. All commands are written to a blocking queue, and a separate thread periodically reads from that queue and delegates tasks.

Commands have arguments that can be given in any order and must be prefixed with “--” (long form) or a single dash “-” (short form). The command can be written as:

`SCAN --min 10.0 --max 20.0 --letter H --output output.txt --job job1`  
or shorter:
`SCAN -m 10.0 -M 20.0 -l H -o output.txt -j job1`

The system must validate all received commands and, if a command is incorrect or the required arguments are missing, print a clear error (without a stack trace) and continue working.

The CLI thread must not be blocked at any time.

#### 

#### 1.3.1 Command `SCAN`

Searches all files in the monitored directory and finds weather stations whose name starts with the given letter and whose temperature is in the range \[min, max\]. Each file should be processed with one thread inside the ExecutorService, where they are later written to the output file (output) whose name is given as an argument to the command `SCAN`.   
Make sure that the results of finding stations for files are not saved and combined in memory because it is working with large files ([Java OutOfMemoryError](https://docs.oracle.com/javase/8/docs/technotes/guides/troubleshoot/memleaks002.html)).  
*Arguments:*  
`--min` (or `-m`): minimal temperature
`--max` (or `-M`): maximal temperature
`--letter` (or `-l`): the first letter of the meteorological station  
`--output` (or `-o`): name of the output file  
`--job` (or `-j`): name of the job 
*Example:*  
`SCAN --min 10.0 --max 20.0 --letter H --output output.txt --job job1`  
*Output file line:*  
`Hamburg;12.0`

#### 1.3.2 Command `STATUS`

Displays the current status of the task (pending, running or completed) with the given name.  
*Argumenti:*  
`--job` (or `-j`): name of the job  
*Example:*  
`STATUS --job job1`  
`job1 is running`

#### 1.3.3 Command `MAP`

It prints the contents of the in-memory map - in 13 lines, where each line shows two letters with the associated number of stations and the sum of measurements. Take care of the situation when the map is unavailable, at the moment when the values ​​are entered for the first time, then it is necessary to print a message that the map is not yet available.
*Example:*  
`MAP`  
`a: 8524 - 1823412 | b: 5234 - 523512`  
`c: 8523 - 5521342 | d: 1253 - 502395 …`  

#### 1.3.4 Command `EXPORTMAP`

Exports the contents of the in-memory folder to a log CSV file. CSV file contains columns: "Letter", "Station count", "Sum". Each line of the log file, which is stored within the project at an arbitrary location, should contain data in the format: 
`a 8524 1823412`  
`b 5234 523512 …`  
*Example:*  
`EXPORTMAP`

#### 1.3.4 Command `SHUTDOWN`

It halts the entire system in an elegant way - terminating all ExecutorServices and signaling all threads to finish their work gracefully. Additionally, if the option is added:
`--save-jobs` (or `-s`), all unexecuted jobs are saved in a separate `load_config` file. You can save unexecuted jobs in any format.
*Example:*  
`SHUTDOWN --save-jobs`

#### 1.3.5 Command `START`

Starts the system. The additional option `--load-jobs` (or `-l`) allows, if there is a file (`load_config`) with saved jobs, those jobs are loaded and automatically added to the execution queue. If the job cannot be started, because for example someone else is working with the file, it is not allowed to discard that job.
*Primer:*  
`START --load-jobs`

### 

### 1.4 Periodic report

It is necessary to implement an additional thread that will, every minute, generate an automatic report on the current state of the in-memory map and write it to a log CSV file (the same file used for `EXPORTMAP`) command. Take care that the report is not mixed, i.e. not executed at the same time, with manual logging (via `EXPORTMAP` command).

### 1.5 Executing tasks via ExecutorService

Divide tasks related to file processing (eg reading files, searching data) into smaller tasks that are executed in parallel. It is recommended to use fork/join pool or classic thread pool (minimum 4 threads).

Each task must have a unique identifier (task name) for status tracking.

## 2 Technical requirements and guidelines

* Files are read part by part (using streams or BufferedReader) so as not to overload the memory.
* Use appropriate mechanisms to ensure that file operations and map updates are not performed concurrently with search and result export operations.
* The system must NOT crack at any time.
 All exceptions must be handled politely - print short, clear messages to the user (eg "Error reading file 'name\_file'. Continuing work."), without displaying the complete stack trace.
* Command arguments can be given in any order, and are recognized by prefixes (eg `--min' or `-m', `--output' or `-o', etc.). The system must validate the arguments and in case of an error notify the user without crashing the system.
* The **`SHUTDOWN`** command must terminate all threads in an orderly fashion. If `--save-jobs' option is added, all unexecuted jobs are saved in a separate file.
* It is recommended that all jobs be entered in the central blocking queue. Components such as a thread that detects directory changes and a thread that accepts commands from the command line add jobs, while a separate, second thread takes over jobs and delegates them on.

## 


## 3 Points on homework \- max 20 points

All parts of the code are scored partially. In order for the points to be recognized, it is necessary to demonstrate the done part.
**\- Directory monitoring and file processing:** 5 points

* The system properly listens to the given directory (defined via command line or configuration file) and detects new or changed files with the extension .txt and .csv.
* Files are read line by line or in smaller segments, without loading the entire file into memory (essential for large files).
* The system correctly skips the header in CSV files.

**\- The system correctly skips the header in CSV files: 5 points

* Using ExecutorService where each thread processes one file.  
* Combining data into an in-memory map organized alphabetically, where the number of stations and the sum of all measurements are recorded for each letter.
* Implement a synchronization mechanism that ensures that map update operations do not collide with data searches or exports.

**\- CLI thread and flexible argument parsing:** 2 points

* A thread that reads commands from a blocking queue, without blocking the user interface.
* Support for entering arguments in any order with prefixes - long form (eg `--min`, `--output`, `--job`) and short form (eg `-m`, `-o`, `-j`).
* The system validates the received arguments and, in case of an error, prints clear messages (without displaying the complete stack trace) and continues work.

\- Commands SCAN, STATUS, MAP, EXPORTMAP:** 6 points
\- **Periodic report:** 2 points

**Negative Points**
\- There is no graceful shutdown and the program does not terminate: \-2 points
\- The program throws an exception (or prints a stack-trace) that interrupts normal operation: \-2 points

