# Project: JAVA LINDA

Design a distributed computing system that enables distributed processing and mutual remote synchronization of time-consuming jobs. This synchronization should be modeled after the virtual shared tuple space, common to all jobs within a set of jobs, that exists in the C-Linda library.

The program should run on a system consisting of multiple computers connected in a LAN (Local Area Network) or WAN (Wide Area Network).

## Program types

There are three types of programs in the system:

1. **Central server** – monitors the execution of distributed processing, stores information about available nodes in the network, and provides the ability to restart individual jobs.
2. **Workstation** – receives jobs to be executed from the central server.
3. **Client program** – submits the job to be executed along with its parameters.

## Job flow

The process begins with the client program submitting a job that needs to be processed. This job is a program written in the Java programming language, packaged in a jar archive. The client program then contacts the central server and forwards the job and the parameters required for its processing. When the central server receives the job and its parameters, it forwards it to one workstation, waits for the processing result, and returns the complete result to the client.

When a workstation receives a job, it begins executing it based on the received parameters. The job is executed on the workstation by launching the job given as a jar archive and passing it the parameters specified by the client. As a separate parameter, the workstation sets the path to the jar archive containing the remote synchronization library. This library is implemented after the model of the C-Linda library, which is used for synchronization. The interface that must be satisfied is given in the appendix of this document.

Since a given client job can spawn multiple jobs (the `eval(...)` method), these should be distributed to free workstations. When program execution finishes, the workstation forwards the collected output streams to the server, along with the execution results.

## Fault detection

To keep track of active workstations, the central server checks every *x* seconds whether each workstation is operational. If a workstation is not operational, the server notifies the user which job has stopped executing. The user then decides whether the job should be terminated or whether the interrupted part should be forwarded to another free workstation. If the user is not available, the execution of the entire job is aborted.

## Client connection

After sending a processing request, the client program may disconnect from the central server. The connection can be broken by shutting down the program or by closing the communication channel. The next time it connects, the client program can request the results of the previously submitted processing. The central server must be able to receive a large number of jobs to be processed in parallel. The client program can request job status information from the central server, as well as the results.

## Workstation registration

Immediately upon startup, workstations send the central server information that they have started, the characteristics of the platform they are running on (operating system, Java version), and the number of jobs they can process in parallel.

## Job parameters

The job parameters specified by the client are:

- the command passed to the Java Virtual Machine to launch the archive,
- files that need to be transferred from the client machine to the workstation so the commands can be executed (no more than 6),
- files that need to be transferred from the workstation back to the client machine, representing the results (no more than 6).

These parameters can be specified either through the user interface or via a text file.

## Logging and job status

The central server writes to its log the time each job arrived, the number under which the job is stored, the name of the computer the job was forwarded to, the time the job finished, and its current status. A job's status can be:

| Status | Meaning |
|---|---|
| **Ready** | arrived at the server, but not yet forwarded to anyone |
| **Scheduled** | currently being forwarded to a workstation |
| **Running** | execution is in progress |
| **Done** | the job executed successfully |
| **Failed** | the job could not be executed |
| **Aborted** | the user cancelled the job's execution |

## Constraints

The problem must be solved exclusively using Java NET network communication. The solution must be independent of the job being performed. Each of the three types of computers must have a corresponding graphical user interface (the GUI should be developed using Java Swing components or JavaFX). The workstation must also be able to run without a user interface.

## Interface  

    package rs.ac.bg.etf.kdp;
    public interface Linda extends Serializable {
    /**
    * Ubacuje torku u prostor torki. Nije dozvoljeno slati bilo koji
    * string koji je null
    */
    public void out(String[] tuple);
    /**
    * Dohvatanje torke iz prostora torki. Ovo je blokirajuca operacija.
    * Ukoliko je neko od polja unutar ovog niza postavljeno na vrednost
    * null onda to polje takodje treba popuniti. Ukoliko ima vise torki
    * uzima se bilo koja.
    * Nakon ove operacije torka se vise ne nalazi u prostoru torki.
    */
    public void in(String[] tuple);
    /**
    * Dohvatanje torke iz prostora torki. Ovo je neblokirajuca operacija.
    * Ukoliko je neko od polja unutar ovog niza postavljeno na vrednost null
    * onda to polje takodje treba popuniti. Ukoliko torka postoji onda se kao
    * rezultat vraca vrednost true, u suprotnom se vraca vrednost false.
    * Ukoliko ima vise torki uzima se bilo koja. Nakon ove operacije torka
    * se vise ne nalazi u prostoru torki.
    */
    public boolean inp(String[] tuple);
    /**
    * Cita torku iz prostora torki. Ovo je blokirajuca operacija. Ukoliko je
    * neko od polja unutar ovog niza postavljeno na vrednost null onda to
    * polje takodje treba popuniti. Ukoliko ima vise torki uzima se bilo
    * koja. Nakon ove metode torka se i dalje nalazi u prostoru torki.
    */
    public void rd(String[] tuple);
    /**
    * Cita torku iz prostora torki. Ovo je neblokirajuca operacija. Ukoliko
    * je neko od polja unutar ovog niza postavljeno na vrednost null onda to
    * polje takodje treba popuniti. Ukoliko torka postoji onda se kao
    * rezultat vraca vrednost true, u suprotnom se vraca vrednost false.
    * Ukoliko ima vise torki uzima se bilo koja. Nakon ove operacije torka
    * se i dalje nalazi u prostoru torki.
    */
    public boolean rdp(String[] tuple);
    /** Pokretanje nove niti na datom racunaru. */
    public void eval(String name, Runnable thread);
    }

