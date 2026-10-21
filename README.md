# parley-ftp

An implementation of the FTP protocol (client and server), part of **Parley**, a family of Java
libraries for implementing internet protocols.

Requires Java 11 or later and Maven 3.6.3 or later. Depends on `parley-files` and `parley-net`
(which bring in `parley-core` and `parley-io`).

```xml
<dependency>
    <groupId>us.bringardner.parley</groupId>
    <artifactId>parley-ftp</artifactId>
    <version>1.0.0</version>
</dependency>
```

> parley-ftp was previously `us.bringardner:bjl_net_ftp` (BjlNetFtp), with packages under
> `us.bringardner.net.ftp`. Moving over means changing the dependency and replacing
> `us.bringardner.net.ftp` with `us.bringardner.parley.ftp` in imports and in property names
> that start with a class name.

## Build

```
mvn verify
```

This compiles, runs the tests and runs SpotBugs. The build fails on any new SpotBugs finding. Accepted findings, each with its reason, are in `spotbugs-exclude.xml`.

## Server configuration

Each setting can be passed as a system property or set with the matching `FtpServer` setter.

| Property | Default | Meaning |
|---|---|---|
| `parley.ftp.port` | 21 | Control port (used by `FtpServer.main`) |
| `parley.ftp.root` | `/ftp` (`C:/ftp` on Windows) | FTP root directory |
| `parley.ftp.dataTimeout` | 600000 ms | A transfer that moves no data for this long is aborted with a 426 reply. This is also how long PASV waits for the client to connect. `setDataTimeout()` |
| `parley.ftp.connectTimeout` | 15000 ms | Connect timeout for active mode (PORT/EPRT). `setConnectTimeout()` |
| `parley.ftp.bufferSize` | 65536 bytes | Transfer buffer size. `setBufferSize()` |
| `parley.ftp.passiveMinPort` / `parley.ftp.passiveMaxPort` | 10333 / 65333 | Passive (PASV/EPSV) port range; busy ports are skipped |
| `parley.ftp.externalAddress` | control connection address | Address advertised in PASV replies (for NAT) |
| `parley.ftp.allowForeignDataAddress` | false | Allow PORT/EPRT to other hosts or ports below 1024, and PASV connections from other hosts (needed only for FXP). `setAllowForeignDataAddress()` |
| `parley.ftp.symlinkPolicy` | `strict` | How symbolic links inside a user's root are treated: `strict`, `allowedTargets` or `follow` (see below). `setSymlinkPolicy()` |
| `parley.ftp.allowedLinkTargets` | none | Comma-separated directories that links may lead into when the policy is `allowedTargets`. `setAllowedLinkTargets()` |
| `parley.ftp.uploadMode` | `atomic` | How `STOR` puts an upload in place: `atomic` or `inPlace` (see [Uploads and resuming](#uploads-and-resuming)). `setUploadMode()` |
| `parley.ftp.storeAtOffset` | false | `REST n` then `STOR` writes in place from `n` (random access); see [Writing at an offset](#writing-at-an-offset). `setStoreAtOffset()` |
| `parley.ftp.virtualHosts` | none | Virtual hosts for `HOST` (RFC 7151): `name=root;name=root` (see below). `addVirtualHost()` |
| `parley.ftp.loginFailureDelay` | 1000 ms | Delay before replying to a failed PASS/ACCT. `setLoginFailureDelay()` |
| `parley.ftp.linger` | off | SO_LINGER (seconds) for data connections |
| `parley.ftp.secure` | false | `main` starts a server with implicit TLS (its port is `parley.ftps.port`) |
| `parley.ftp.*` | | The server's own settings use its prefix: the key store (`keyStoreName`, `keyStorePassword`, `keyStoreType`), the limits of parley-core's `AbstractCoreServer` (`maxConnections`, `maxLoginAttempts`, ...) and the access control list (`authenticationProvider`, `userFile`). A server with implicit TLS uses `parley.ftps`. |

### Behaviour notes

- **Paths are confined to the user's root.** `..` stops at `/`, as in a chroot, in every mode.
- **Symbolic links** are how an administrator makes files visible to users without copying them. They are covered in [Links](#links) below: clients don't know a link is one, and what they can do to it.
- **STOR is safe to retry.** By default data is written to a hidden temporary file (`.name.<id>.ftp-part`), which replaces the target only when the upload completes. What that means for resuming is in [Uploads and resuming](#uploads-and-resuming).
- **Transfer replies:**
  - A successful transfer gets `150` then `226`.
  - ABOR gets `426` then `226`.
  - A stalled or failed data connection gets `426`.
  - A local file system error gets `451`.
- **STAT, REIN and ALLO (RFC 959):**
  - `STAT` gives the session's status (`211`: login, TYPE, data connection, TLS, current directory); during a
    transfer, the bytes transferred so far (`213`). `STAT path` sends what `LIST` would, on the control connection
    (`212` for a directory, `213` for a file); it needs a login.
  - `REIN` lets a transfer in progress finish, then logs out and resets the session (TYPE, data connection, `REST`,
    `RANG`, the user's root and directory) and replies `220`; `USER` comes next. After `AUTH TLS` the reply is sent
    protected and the control connection then goes back to clear text (RFC 4217 section 13); with implicit TLS it stays
    TLS. Failed logins still count.
  - `ALLO` is accepted and does nothing (`202`): space is allocated as the data arrives.
- **The rest of RFC 959:**
  - `STOU` stores the upload under a name not yet used in the current directory and says which in its reply
    (`150 FILE: name`, RFC 1123). With an argument it starts from that name (`name`, `name.1`, ...), otherwise
    `ftp-<time>`. The name is made before the transfer and removed again if the upload fails.
  - `XCWD`, `XMKD`, `XPWD`, `XRMD` and `XCUP` (RFC 775) are the same as `CWD`, `MKD`, `PWD`, `RMD` and `CDUP`.
  - `TYPE A [N]`, `I`, `L 8` (the same as `I`) and `U` (UTF-8 text, draft-klensin-ftp-typeu; sent as stored, like
    `A`) are accepted; `TYPE E`, other `L` sizes, `A T` and `A C`, `MODE B`/`C` and `STRU R`/`P` get `504` (not
    implemented for that parameter). `SMNT` gets `502`.
  - `LPRT` and `LPSV` (RFC 1639, historic) are the long address forms of `PORT` and `PASV`, IPv4 or IPv6.
- **LANG (RFC 2640, `LANG EN*` in `FEAT`):** replies are in English only: `LANG`, `LANG EN` and English variants
  (`en-US`) get `200`, another language `504`.
- **Hashes:**
  - `HASH path` (draft-ietf-ftpext2-hash, in `FEAT` as `HASH SHA-256*;SHA-512;SHA-1;MD5;CRC32`) replies
    `213 SHA-256 0-1023 <hex> path`: the hash of what `RETR` in `TYPE I` sends. `OPTS HASH name` chooses the
    algorithm (`REIN` goes back to SHA-256); `RANG start end` first hashes part of the file. A long hash sends a `213-`
    line every 5 seconds. `550` no such file, `553` a directory, `450` while a transfer is in progress.
  - `XCRC`, `XMD5`, `XSHA1`, `XSHA256` and `XSHA512 path [start [end]]` (as ProFTPD, Serv-U, FileZilla and WinSCP
    have them; `end` not included) reply `250 <hex>`. The path may be in double quotes.
- **Changing times and facts (draft-somers-ftp-mfxx, each in `FEAT`):** `MFMT time path` and `MFCT time path` set the
  modification and creation times (RFC 3659 time-val, UTC); `MFF fact=value;...; path` sets `Modify`, `Create`,
  `UNIX.mode` (octal), `UNIX.owner` and `UNIX.group`. Replies (`213 Modify=...; path`) show the values as they are after
  the change. Owner and group need the ADMIN permission, the rest WRITE. An unknown fact gets `504` and nothing changes;
  an invalid time (February 30 included) `501`; no such file `550`; a fact the file system won't set `450` (MFF isn't
  atomic). A link gets a copy in its place, as with `SITE CHMOD`.
- **draft-peterson-streamlined-ftp-command-extensions (each in `FEAT`):**
  - `AVBL [dir]`: octets that can be uploaded there (the file system's usable space), `213 n`.
  - `DSIZ [dir]`: octets in the files of a directory and all below it, `213 n`; links aren't followed.
  - `RMDA dir`: removes a directory and everything in it (WRITE permission, as `RMD`); a link is removed, not
    followed; `550` if anything is left, or for a file or the root.
  - `THMB format width height path`: a thumbnail of an image, no larger than that (aspect kept, never enlarged), sent
    on the data connection as `RETR` sends a file; formats are what Java writes (`BMP|GIF|JPEG|PNG`). A source image
    whose header says it has more than 50 million pixels is refused (`550`) before it is decoded.
  - `CSID Name=...;Version=...;Vendor=...;`: the client says what it is (shown by `STAT`), the server replies the same
    of itself. Needs a login.
- **CLNT name** (in `FEAT`; FileZilla, Serv-U and others have it, no RFC): the client says what it is; `STAT` shows it.
- **SPSV (draft-rosenau-ftp-single-port, in `FEAT`):** passive mode through the control port only. The reply has a
  one-time identifier, `227 Entering single-port mode (id)`; the client opens a second connection to the control port,
  sends `SPDT id`, gets `200 DATA`, and that connection carries the next transfer. As with PASV, it must come from the
  client's own address (`parley.ftp.allowForeignDataAddress` aside). It is protected as the control port
  is, so `SPSV` gets `504` when the data protection set differs (`PROT P` after `AUTH TLS`, `PROT C` with implicit TLS).
- **MODE Z (draft-preston-ftpext-deflate, `MODE Z` in `FEAT`):** data connections (transfers and listings) are
  deflated (zlib); `OPTS MODE Z LEVEL n` sets the level (0-9); `MODE S` turns it off, as does `REIN`. `REST` and `RANG`
  get `504` in MODE Z.
- **Virtual hosts (`HOST`, RFC 7151, in `FEAT`):** a client sends `HOST name` before logging in and gets that host's
  root (`addVirtualHost(name, root)` or `parley.ftp.virtualHosts`; each root can be in its own file system). Users and
  their permissions are the server's, whatever the host.
  - With no virtual hosts, any name is accepted (`220`) and is the server. With some, a name that isn't one of them
    gets `504` and the session stays on the host it had. A client that sends no `HOST` gets the server's root.
  - An IP address must be this server's (`504` otherwise) and is the server's own host; IPv6 goes in brackets.
    A port, or anything that isn't a host name, gets `501`; `HOST` after logging in gets `503`.
  - A later `HOST` replaces an earlier one and forgets a `USER` already sent; `REIN` forgets the host.
  - With TLS the name must match the one the client asked for (SNI): `HOST` after `AUTH TLS` gets `504` if it
    doesn't; `HOST` before `AUTH TLS` that doesn't gets `421` once TLS is up, and the connection is closed. There is
    one certificate for all hosts.
- **Logging** defaults to INFO.
- **TLS (RFC 4217):**
  - **Explicit TLS:** `AUTH TLS` on the normal port. Data connections are clear until the client sends `PBSZ 0` and `PROT P`.
  - **Implicit TLS:** `setSecure(true)`. Data connections use TLS unless the client sends `PROT C`.
  - **PROT levels:** only `C` and `P` are supported; `S` and `E` get `536`.
  - **CCC** clears the control connection after `AUTH TLS` (`533` otherwise; implicit TLS can't be cleared); `PBSZ`
    and `PROT` stay, so data connections are protected as before. After the reply to `CCC` (and to `REIN`), TLS is
    shut down both ways as RFC 4217 section 12.3 shows: the server sends close_notify and reads the client's. A client
    that sends none should wait 2 seconds before its next command, or that command is lost.
- **Failed logins** wait `parley.ftp.loginFailureDelay` ms (default 1000) before the reply, and a connection is closed after 3 failures.

## Uploads and resuming

`STOR` puts an upload in place in one of two ways, set with `parley.ftp.uploadMode` / `setUploadMode()`. They
trade two things against each other: a file that is never half replaced, and an upload that can be resumed.

| | `atomic` (default) | `inPlace` |
|---|---|---|
| Where the data goes | A hidden temporary file in the same directory, renamed over the target when the whole upload has arrived | Straight into the target, which `STOR` empties first |
| An upload that breaks | The temporary file is deleted. The target is untouched (a new file doesn't exist). | What arrived stays in the target. |
| Resuming it | **Not possible.** `REST` then `STOR` is refused (`450`) and the client starts again. | `REST <length>` then `STOR` carries on. |
| The original while it uploads | Still there, complete | Gone as soon as the upload starts; a half written file is visible |

**`REST` then `STOR` carries on only an upload the server knows is unfinished.** It is accepted when the
marker equals the file's length *and* an earlier upload of that file stopped at that length (a break in
`inPlace` mode; the server remembers up to 1000 such files, in memory, so after a restart a client starts
again). Otherwise it is refused (`450`) and nothing is written. A complete file can't be added to with
`REST`. This used to be possible, and it corrupted files: a client that resumes by asking `SIZE` and sending
the rest from that offset, after an upload that was interrupted *over an existing file*, got the old file's
length, the marker matched, and the new data was appended to the old file, which is neither version.

- **`APPE` is unchanged**: it always appends to the file, so a client that resumes with `APPE` and the
  tail of a local file adds to whatever is there. Use `REST` and `STOR` for resuming.
- **`SIZE`** gives the length of the file that exists, which in `atomic` mode is the old file, not what had
  arrived.
- A file changed after the break (so its length is no longer where the upload stopped) is not resumed.

### Writing at an offset

With `parley.ftp.storeAtOffset` / `setStoreAtOffset(true)` (off by default), `REST n` then `STOR` writes the
upload into the file from `n`, in place, and leaves the rest of the file as it was. The FTP standards don't say what
a server does with `REST` then `STOR`, and other servers differ (some refuse, some replace the file, some add to it).
A client can then write a block in the middle of a file, which
is how parley-files-ftp gives read-write random access (`FtpClient.getOutputStream(path, ascii, startingPos,
bufferSize)` sends the `REST` and `STOR`).

- `n` may be anything from 1 to the file's length; past the end it is refused (`554`). `n` equal to the
  length adds to the file, **even one that wasn't cut off**: the check described above is off with it.
- The write isn't atomic, whatever `parley.ftp.uploadMode` is: a transfer that breaks leaves what arrived.
- `REST 0` is no restart: `STOR` replaces the file, as always.
- On a link it is refused (`450`), as before.

### Octet ranges (RANG)

The server supports `RANG start end` from [draft-bryan-ftp-range-08](https://datatracker.ietf.org/doc/html/draft-bryan-ftp-range-08),
and lists it in `FEAT` as `RANG STREAM`. It is an expired Internet-Draft, not an RFC, so other servers and
clients may not have it. Both points count octets from the start of the file, and the end is inclusive:
`RANG 0 1` is the first two octets.

- A good range gets `350` and is used by the next `RETR` or `STOR` only. `RANG 1 0` resets it (`350`); a start
  after the end, or anything but two numbers, gets `501` and resets it too.
- `RANG` and `REST` replace each other: the one sent last is the one the transfer uses.
- `RETR` sends the range. An end past the end of the file sends to the end of the file; a start at or past it
  sends nothing.
- `STOR` writes the upload into the file from the start, in place, and changes nothing outside the range: the
  file isn't truncated, and more data than the range holds fails the transfer (`451`) once the range is
  written. A start past the end of the file is refused (`554`). On a link it is refused (`450`).
  Unlike `REST` then `STOR`, the range says exactly which octets are written, so this doesn't need
  `parley.ftp.storeAtOffset`.
- `APPE` refuses a range (`504`).

## Writers

Two sessions never change one file at once, whether they are on this server or on others that share
the store:

- An upload (`STOR`, `STOU`, `APPE`, a resumed or ranged `STOR`) holds the file's lock from the command
  until the transfer ends. A `STOR` that writes a temporary file holds it until the temporary file has
  replaced the target, in one step. Removing a file (`DELE`) and renaming one (`RNFR`/`RNTO`, both the old
  and the new name) take the lock too.
- While another session (or this one, in a second transfer) holds it, the command is refused at once
  with `450` and "the file is busy (another upload or change is under way); try again later". Nothing
  waits.
- Downloads take no lock: a reader sees the file as it is being written, or, for a `STOR` through a
  temporary file, the old file until it is replaced.
- The locks are parley-net's (`StoreLocks`): one lock domain per root, shared with the other servers in
  the JVM that serve it (parley-ssh's SFTP server, say), so an FTP upload and an SFTP write of one file
  keep out of each other too. A root inside one already in use shares its domain.
- How many servers share the store is the system property `parley.ftp.sharedStore`: `off` (the default:
  this server alone), `host` (several on one machine) or `cluster` (several anywhere). At start the
  server claims the store of its root and of every virtual host; with `off`, a server elsewhere that
  claims it is refused, and this one doesn't start if the store is already claimed alone. With `host` or
  `cluster`, every server must serve the same root, where the locks are kept. `parley.ftp.serverId` names
  this server among them (default: the host, the name and the port).
- The locks are kept in the root, in `.parley-net-locks`. The server hides it: it isn't listed and can't
  be reached, so no client can change it.
- Not covered: a directory removed or renamed while a file in it is uploaded (the upload then fails as it
  would without locks), two names for one file on a file system that ignores case, and a virtual host
  added after the server started (its files are locked, but its store isn't claimed).

Tested in `TestFtpLocks`.

## Links

An administrator can make a file or a directory appear in a user's root without copying it, by making a
**symbolic link** to it (`ln -s /data/reports/q3.pdf /ftp/alice/q3.pdf`). FTP has no command to make a link,
so links are made outside FTP: on the file system, through SFTP, or through the `FileSource` API.

**The clients are not to know.** FTP clients don't know about links, and the server doesn't tell them. A
link is shown, and acts, as the file or directory it leads to.

> **This is the FTP model, and only FTP's.** The FTP protocol has no idea of a link: a client can't see
> one, make one or read one, so the server hides them and decides for itself what a write to one means
> (below: it replaces the link). A protocol that is aware of links has other rules. SFTP lets a client see
> a link (`lstat`, `readlink`) and make one (`symlink`), and a write through a link changes the file it
> leads to, as it does in the `FileSource` API (see [parley-files](https://github.com/parley-project/parley-files#links)).
> Nothing in this section applies to them.

### What a client sees

| Client does | Result |
|---|---|
| `LIST`, `NLST`, `MLSD`, `MLST` | An ordinary file (`-rw-r--r--`, `type=file`) or directory (`d`, `type=dir`) with the size, time, owner and permissions **of what it leads to**. No `->`, no `l`, no `OS.unix=slink`. |
| `RETR`, `SIZE`, `MDTM`, `CWD`, `PWD` | As for the file or directory. `PWD` shows the client's path (`/q3.pdf`), never the server's real path. |
| A refused command | A reply that doesn't say the file is a link. No reply carries the server's real path (the tests check this). |

### What a client can do to it

What a client does to a link never changes the file it leads to.

| Client does | Result |
|---|---|
| `DELE`, `RMD`, `RNFR` + `RNTO` | Acts on the **link**. The linked file or directory is untouched. |
| `STOR` | The link is **replaced by a regular file** that holds the upload. The original is untouched. |
| `APPE`, `SITE CHMOD`, `SITE modDate` | The link is first replaced by a regular **copy** of the file (same content, permissions and time), and the command applies to that copy. The original is untouched. |
| `REST` then `STOR` | Refused (`450`): a link isn't an unfinished upload (see [Uploads and resuming](#uploads-and-resuming)), and isn't written at an offset either. Nothing changes. |
| `STOR`, `MKD`, `DELE` **inside** a linked directory | Happens in the directory it leads to. The link is the way in, and a client with write permission changes what is behind it, as it would in any directory it can write to. |
| `SITE CHMOD`, `SITE modDate` on a linked **directory** | Refused (`450 Permission denied.`). A directory isn't copied. |

So a client that writes to a linked file ends up with its own regular file in the same place, and the
administrator's link, and the original, are not harmed.

### Making a link read-only

The administrator's control is permissions, the same as for any file:

- **A file's write permission is honored.** `STOR` and `APPE` to a file that can't be written are refused
  (`550 Permission denied.`). A link has the permissions of the file it leads to, so make that file
  read-only (`chmod a-w`) and the link is read-only for the users, whichever of their roots it appears in.
- **A directory that can't be written keeps its entries.** A link in a directory without the write
  permission can't be deleted, renamed or replaced (`450`, `550`, `553`), as for any file in it.
- A symbolic link has no permissions of its own on most systems, so there is nothing to set on the link
  itself.

To keep the contents of a linked *directory* from being changed, make that directory (and what is in it)
read-only. The server doesn't treat everything behind a link as read-only, so a linked drop folder can
accept uploads.

### Where a link may lead

`parley.ftp.symlinkPolicy` decides whether a link may lead outside the user's root:

- `strict` (default): a link that leads outside the root is refused, so it can't be used to reach other files.
- `allowedTargets`: links may lead outside the root only into the directories listed in `parley.ftp.allowedLinkTargets`,
  e.g. to share `/data/shared` in every user's root. **This is the setting for linking in files from elsewhere.**
- `follow`: every link inside the root is followed. Use it only when users can't create links by other means
  (shell, SFTP, Samba, unpacked archives).

`..` never leaves the root. Hard links need no setting; they are ordinary files inside the root.

### The client library reads links too

The client (`FtpClient`, `FtpClientFile`, `ListEntry`) understands the two ways a server can say an entry is
a link, so it works against servers that do: a `LIST` line `name -> target` and an `MLST`/`MLSD` type
`OS.unix=slink:target`. `ListEntry.getType()` is `ListEntry.TYPE_LINK` and `getLinkTarget()` is where it
points. This server doesn't say, for the reasons above.

## Client notes

- **Passive mode:**
  - The client tries `EPSV` first (RFC 2428; needed for IPv6) and falls back to `PASV` for the rest of the session if the server doesn't support it (`setUseEpsv(false)` to always use PASV).
  - Data connections go to the control connection's host; `setUsePasvAddress(true)` uses the PASV reply's address instead.
- **Active mode** (`setActive(true)`): the client listens on the control connection's local address and sends `PORT` (IPv4) or `EPRT` (IPv6). With a protected data channel the client is the TLS client, as RFC 4217 requires.
- **One transfer per connection:** while a stream from `getInputStream`/`getOutputStream` is open, any other command on the same `FtpClient` throws `IOException`. Use one `FtpClient` per concurrent transfer.
- **Reconnect:** if the server has closed the control connection (e.g. idle timeout), the next command reconnects, logs in again (including AUTH), restores the current directory and retries safe commands (NOOP, PWD, CWD, TYPE, SIZE, …) once. Turn this off with `setAutoReconnect(false)`.
- **More commands:** `status()` and `status(path)` (STAT; a listing on the control connection), `nameList(path)`
  (NLST), `reinitialize()` (REIN, then logs in again on the same connection; after `AUTH TLS` TLS is ended and
  negotiated again) and `clearCommandChannel()` (CCC: the control connection clear, data connections still protected;
  TLS shut down both ways).
- **ABOR:** closing a download before its end sends `ABOR`, so the server stops at once, and reads both replies.
  `setAbortOnClose(false)` just closes the data connection.
- **CLNT:** after logging in, a server that lists `CLNT` in `FEAT` is told `parley-ftp <version>`
  (`setClientName()`); `setSendClientName(false)` doesn't.
- **SPSV:** `setUseSinglePort(true)` uses `SPSV` and `SPDT` for passive data connections when the server lists `SPSV`
  in `FEAT` and accepts it; otherwise `EPSV`/`PASV`. Off by default.
- **MODE Z:** `setCompression(true)` deflates data connections with a server that lists `MODE Z` in `FEAT` (now, and
  after a reconnect). A download from a position then reads and skips up to it; an upload at a position fails. Off
  by default.
- **Streamlined extensions:** `getFreeSpace(dir)` (AVBL), `getDirectorySize(dir)` (DSIZ), `deleteRecursively(dir)`
  (RMDA), `getThumbnail(path, format, width, height)` (THMB) and `identify(name, version, vendor)` (CSID), each when the
  server lists the command in `FEAT`.
- **Times and facts:** `setModificationTime(path, time)` and `setCreationTime(path, time)` use `MFMT`/`MFCT`, else
  `MFF`, with milliseconds when the server takes them, else whole seconds; `setFacts(path, facts)` uses `MFF`. `FtpClientFile.setLastModified()` uses `MFMT` when the server has it
  (`setUseModifyFacts(false)`: always parley's `SITE` command, as before).
- **Hashes:** `hash(path[, algorithm[, start, end]])` returns the server's hash of a file or part of one, using
  `HASH` (with `OPTS HASH` and `RANG`) when the server lists it in `FEAT`, else the X commands.
- **LANG:** `setLanguage(tag)` asks a server that lists `LANG` in `FEAT` for that language, now and after a reconnect;
  `getLanguages()` lists what it offers.
- **STOU:** `storeUnique(baseName, ascii, bufferSize)` returns a stream whose `getStoredName()` is the name the server
  chose.
- **LPSV / LPRT** are tried when a server refuses `PASV` (after `EPSV`) or `EPRT`.
- **HOST (RFC 7151):** the client sends `HOST` before `AUTH` and `USER`: the name it connects to, or
  `setVirtualHost(name)`. A virtual host is also the name used for TLS (SNI and the certificate check). A server that
  refuses the name it connects to, or doesn't know `HOST`, is logged in to as usual; one that refuses a virtual
  host that was set makes `connect()` fail. `setSendHost(false)` doesn't send it.
- **Streams** returned by `getInputStream`/`getOutputStream` are buffered.
- **Ranges (RANG):** `getInputStream(path, ascii, start, end, bufferSize)` reads the octets from `start` to `end`
  (inclusive). It sends `RANG start end` when the server lists `RANG` in `FEAT`; with any other server it starts
  at `start` as with `REST` and the stream stops after `end`. `getOutputStream(path, ascii, start, end,
  bufferSize)` sends `RANG start end` straight before `STOR`, so the server writes in place and changes nothing
  outside the range; if the server refuses the `RANG` nothing is stored, and writing more than the range holds
  throws. See [Octet ranges (RANG)](#octet-ranges-rang).
- **SO_LINGER** is off by default (`setCmdLinger`, `setTransferLinger`).
- **TLS:** after logging in over TLS the client sends `PBSZ 0` / `PROT P`; if the server refuses, data connections are clear (`isDataChannelSecure()`). Every new connection negotiates AUTH again.
- **Reply timeouts:** if a transfer's final reply times out, the control connection is dropped and the next command reconnects.
- **Truncated downloads:** `close()` on a download that was read to the end throws if the server reports the transfer failed.
