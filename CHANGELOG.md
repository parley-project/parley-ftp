# Changelog

## parley-ftp 1.1.0 (unreleased)

### Deprecated

Misspelled public names get correctly spelled ones. The old names still work (same values) and will be
removed in 2.0.0.

- `FtpClient.SEPERATOR_CHAR` and `FtpClient.SEPERATOR`: use `FtpClient.SEPARATOR_CHAR` and `FtpClient.SEPARATOR`.
- `Mlst.SEPERATOR`: use `Mlst.SEPARATOR`.
- `FtpServerStream.getBytesTransfered()`: use `getBytesTransferred()`.

### Added

- Writers keep out of each other: an upload, a `DELE` or an `RNTO` of a file another session (here, or on
  a server sharing the store) is changing is refused at once with `450`. Downloads take no lock. The
  server claims the store of its root and its virtual hosts at start. System properties
  `parley.ftp.sharedStore` (`off`, `host`, `cluster`; default `off`) and `parley.ftp.serverId`. The lock
  directory `.parley-net-locks` in the root is hidden from clients. See the README, "Writers".
- `FtpServer.setStoreAtOffset(boolean)` / `JFtp.storeAtOffset` (off by default): `REST n` then `STOR` writes
  in place from `n` and leaves the rest of the file as it was, so a client can write a block in the middle
  of a file. See the README, "Writing at an offset".
- `FtpClient.getOutputStream(path, ascii, startingPos, bufferSize)` and a `ClientFtpOutputStream`
  constructor with a starting position: `REST` then `STOR`. If the server refuses the `REST` nothing is
  stored (a `STOR` without it would replace the file).
- `RANG start end` (draft-bryan-ftp-range-08), listed in `FEAT` as `RANG STREAM`: `RETR` sends that octet
  range, and `STOR` writes it in place (it doesn't need `JFtp.storeAtOffset`).
- `STAT`, `REIN` and `ALLO`, the rest of RFC 959's required commands. `STAT` gives the session's status, or a
  transfer's progress, and `STAT path` lists on the control connection; `REIN` logs out and resets the session
  (after AUTH TLS the control connection goes back to clear text, RFC 4217 section 13); `ALLO` replies `202`.
  See the README, "Behaviour notes".
- `STOU` (store under a unique name, `150 FILE: name`), the RFC 775 forms `XCWD`, `XMKD`, `XPWD`, `XRMD` and
  `XCUP`, `SMNT` (`502`), and `LPRT`/`LPSV` (RFC 1639). Client: `FtpClient.storeUnique()`
  (`ClientFtpOutputStream.getStoredName()`), and `LPSV`/`LPRT` when a server refuses `PASV` or `EPRT`.
- `CLNT` (server, shown by `STAT`). Client: `status()`/`status(path)` (STAT), `nameList()` (NLST), `reinitialize()`
  (REIN, then logs in again), `clearCommandChannel()` (CCC); it sends `ABOR` when a download is closed before its end
  (`setAbortOnClose(false)` to not) and `CLNT parley-ftp <version>` to servers that list it (`setSendClientName(false)`
  to not).
- `SPSV`/`SPDT` (draft-rosenau-ftp-single-port) and `MODE Z` with `OPTS MODE Z LEVEL` (draft-preston-ftpext-deflate),
  both in `FEAT`. Client: `FtpClient.setUseSinglePort()` and `setCompression()`, off by default.
- `AVBL`, `DSIZ`, `RMDA`, `THMB` and `CSID` (draft-peterson-streamlined-ftp-command-extensions). Client:
  `FtpClient.getFreeSpace()`, `getDirectorySize()`, `deleteRecursively()`, `getThumbnail()`, `identify()`.
- `MFMT`, `MFCT` and `MFF` (draft-somers-ftp-mfxx): modification and creation times, `UNIX.mode`, and (ADMIN only)
  `UNIX.owner`/`UNIX.group`. Client: `FtpClient.setModificationTime()`, `setCreationTime()`, `setFacts()`;
  `FtpClientFile.setLastModified()` uses `MFMT` when the server has it, else the `SITE` command as before
  (`FtpClient.setUseModifyFacts(false)` to always use `SITE`).
- File hashes: `HASH` (draft-ietf-ftpext2-hash) with `OPTS HASH` and `RANG`, and `XCRC`, `XMD5`, `XSHA1`,
  `XSHA256`, `XSHA512`; SHA-256, SHA-512, SHA-1, MD5 and CRC32. Client: `FtpClient.hash()`.
- `LANG` (RFC 2640), English only, listed in `FEAT` as `LANG EN*`. Client: `FtpClient.setLanguage()` and
  `getLanguages()`.
- Virtual hosts: `HOST` (RFC 7151), listed in `FEAT`. `FtpServer.addVirtualHost(name, root)` /
  `JFtp.virtualHosts` give each host its own root; the name must match TLS's (SNI); `REIN` forgets it. The client
  sends `HOST` before `AUTH` and `USER` (`FtpClient.setVirtualHost(name)`, else the name it connects to;
  `setSendHost(false)` to not), and a virtual host is also the name used for TLS. See the README.
- `FtpClient.getInputStream(path, ascii, startingPos, endingPos, bufferSize)` and
  `getOutputStream(path, ascii, startingPos, endingPos, bufferSize)` (and `ClientFtpInputStream` /
  `ClientFtpOutputStream` constructors with a range): `RANG` then `RETR` or `STOR`. A download from a server
  without `RANG` starts at the position as with `REST` and stops at the end of the range itself; an upload
  stores nothing if the `RANG` is refused. See the README, "Octet ranges (RANG)".

### Changed

- `TYPE`, `MODE` and `STRU` with a value that isn't implemented get `504` (RFC 959), not `501`; `501` is for bad
  syntax. `TYPE A N`, `TYPE L 8` and `TYPE U` are accepted.
- A `STOR` through a temporary file replaces the target in one step where the store can (it moved the old file aside first, so a download could find no file for a moment).
- Dependencies: parley-net and parley-files 1.1.0.
- The 226 and 426 replies that report a byte count say "transferred" (were "transfered").

### Fixed

- `CCC` and `REIN` end TLS on the control connection both ways (RFC 4217 section 12.3): the server sends close_notify
  and reads the client's, which was taken for a command. `CCC` keeps `PBSZ`/`PROT`, so data connections stay protected
  (they were clear after it), and gets `533` when there was no `AUTH TLS`.

- `STRU` sent a second reply (`215` and the server's name, copied from `SYST`) after its own, so a client read
  it as the reply to its next command.

## parley-ftp 1.0.0 (2026-10-16)

BjlNetFtp is now **parley-ftp**, part of the Parley library family. The code is the same as
BjlNetFtp 1.0.0-SNAPSHOT (below, never released under that name); only names changed.

### Changed (needs a code change)

- Maven coordinates: `us.bringardner:bjl_net_ftp` is now `us.bringardner.parley:parley-ftp`.
- Packages: `us.bringardner.net.ftp` (and `.client`, `.server`, `.server.commands`, ...) is now
  `us.bringardner.parley.ftp`.
- Module name (`Automatic-Module-Name`): `us.bringardner.parley.ftp` (none was set before).
- Property names that start with a class name change with the package, for example
  `us.bringardner.net.ftp.server.FtpServer.<name>` is now `us.bringardner.parley.ftp.server.FtpServer.<name>`.
- Dependencies: `bjl_core`, `bjl_io`, `bjl_file_system` and `bjl_net_framework` are now
  `parley-core`, `parley-io`, `parley-files` and `parley-net`.
- `FtpClientFile.splitMlsxEntry` and `FtpClientFile.mlsxName`, deprecated pass-throughs in BjlNetFtp, are
  removed: use `ListEntry.splitMlsxEntry` and `ListEntry.mlsxName`.

### Added

- `ListEntry` reads symbolic links: a `LIST` line `name -> target` gives the name `name` and the target, and
  an `MLST`/`MLSD` type `OS.unix=slink:target` (or `OS.unix=symlink`) is type `ListEntry.TYPE_LINK`
  (it was read as a directory). `ListEntry.getLinkTarget()` and `FtpClientFile.getLinkTarget()` give the
  target as the server wrote it.
- Stream buffer sizes: `FtpClient.getInputStream(path, ascii, startingPos, bufferSize)`,
  `getOutputStream(path, ascii, append, bufferSize)`, and `FtpClientFile` overloads, take the size of the
  stream's buffer (0 for `getTransferBufferSize()`, at least 8 KB). The streams report it with
  `getBufferSize()`.

- **`JFtp.uploadMode`** (`FtpServer.setUploadMode`): `atomic` (default, as before: the upload goes to a
  temporary file and replaces the target when complete) or `inPlace` (the upload goes straight into the
  target, and one that breaks leaves what arrived, which `REST` and `STOR` can carry on). See the README's
  "Uploads and resuming".
### Changed (no code change needed)

- **A link looks and acts like the file or directory it leads to, and a write never changes the
  original.** An administrator makes files visible to users with links, to avoid copies, and the users are
  not to know. `LIST`, `MLSD`, `MLST` and `NLST` show a link as what it leads to (no arrow, no `slink`
  type, the target's size and facts), and reading, `CWD`, `SIZE` and `MDTM` work through it. `DELE`, `RMD`
  and `RNFR`/`RNTO` on a link act on the link only. A write replaces the link with a regular file, so
  what it led to is never changed: `STOR` stores a new file in its place; `APPE`, `SITE CHMOD` and
  `SITE modDate` first make that regular file a copy of what the
  link led to (with its permissions and time) and then apply to the copy. A linked *directory* isn't
  copied, so `SITE CHMOD` and `SITE modDate` on one are refused (`450 Permission denied.`); work inside it
  happens in the directory it leads to. (A store used to replace the link, an append changed the
  original, and chmod and modDate changed the original's mode and time.) Links that lead outside the root
  still need the symlink policy (`allowedTargets`).
- **`REST` then `STOR` carries on only an upload the server knows is unfinished.** It was accepted for
  any file whose length equalled the marker, and then appended to it. A client that resumes by asking
  `SIZE` and sending the rest of its file, after an upload interrupted over an existing file, was given the
  old file's length, so the new data was appended to the old file, which is neither version. It is now
  refused (`450`) and the client stores the file again. In the default `atomic` mode an interrupted upload
  leaves nothing (as before), so there is nothing to resume; resuming works with the new `inPlace` mode.
  `APPE` is unchanged.
- **Permissions are the administrator's control.** `STOR` now honors a file's write permission: a file
  that can't be written is refused (`550 Permission denied.`) like `APPE` always was. An upload used to
  replace a read-only file, because it goes to a temporary file that is renamed over it. A link has the
  permissions of what it leads to, so a link is made read-only by making the file it leads to read-only,
  and a directory that can't be written keeps its entries (links included) from being deleted, renamed or
  replaced, as for any file.
- `SITE CHMOD` takes owning the file, not being able to write it, so a read-only file can be made writable
  again.

## 1.0.0 (unreleased)

The first release. This section covers the changes since the review that started on 2026-09-29.

Requires Java 11, `bjl_core` 1.3.1, `bjl_io` 1.1.1 and `bjl_net_framework` 1.1.0 (snapshots until
those are released). They are published to GitHub Packages and the pom names the repository.

### Security

- **FTPS clients check the server's host name.** `FtpClient` accepted any certificate its trust
  managers accepted, for any host, with both explicit (AUTH TLS) and implicit TLS. It now does the
  HTTPS host name check and sends SNI (`bjl_core`'s `TlsSockets`). `setVerifyHostname(false)` or the
  `VerifyHostname` property turns the check off for a server whose certificate doesn't match the name
  used to reach it. Data connections resume the control connection's TLS session and are unchanged.
- **No clear text login after AUTH TLS fails.** When the server accepted AUTH but TLS couldn't be set
  up, the client tried the next mechanism on the broken connection and, if the server refused it, logged
  in over the plain connection: a server or attacker in the middle could get the user name (and
  password) in clear text. A failed TLS setup after an accepted AUTH now closes the connection and
  `connect()` throws an `IOException`. A server that refuses AUTH still gets a plain login unless
  `setRequireSecure(true)` is set.
- **Passwords are kept out of logs.** The argument of a `PASS` command is replaced before it reaches
  a log or the session dialog.
- **Replies don't reveal the server's real paths.** Every reply goes through `hideRealPaths()`, so a
  message that quotes a path (an exception's message, say) shows the path the client sees, not where
  the user's root really is on the server.
- **Symbolic links and the user's root (BJL-17).** A path is resolved inside the user's root, and
  `FtpServer.SymlinkPolicy` decides where a link may point. With `STRICT`, the default, its real path
  must also be inside the root, so a link can't be used to reach other files.

### Server

- The `150` reply is sent before the transfer thread starts. A small transfer could finish, and send
  its `226`, before the `150`, which confused clients.
- An unexpected close of the control connection works like `ABOR` (RFC 959): a running transfer is
  stopped and passive listeners and data sockets are released, so no ports, threads or files stay open.
- The passive (PASV / EPSV) listener is closed after its one connection; it used to stay open.
- Active mode (PORT / EPRT) connects with a connect timeout. The timeout used to be passed as the local
  port, so the server tried to bind port 10.
- `SITE` sent two replies to one command, and text with line breaks went out as extra lines without a
  reply code. Multi-line text is sent as an RFC 959 multi-line reply, and each command gets one reply (BJL-26).
- Accounts (BJL-4): a login that needs an account gets `530` (it was `332`, which says the password
  was accepted), and the password is kept for one `ACCT`, which retries the login as `user@account`.
- The greeting is `220` (it was `200`, which curl rejects; BJL-30).
- `LIST` dates are written the way `ls` writes them (BJL-45): English month names whatever the server's
  locale, the day padded with a space ("Oct  1"), the time for files changed in the last six months and
  the year for older (and future) ones.
- `MLSx` `modify=` and `MDTM` times are written as `YYYYMMDDHHMMSS.sss` (BJL-36) in UTC, as RFC 3659
  requires; they used to be in the JVM's time zone, so clients saw times shifted by the server's UTC
  offset (BJL-37). `LIST` dates stay in local time, like `ls`.
- `MLST` sends the whole path name (BJL-48), `FEAT` lists `UTF8` (RFC 2640) and `OPTS MLST` is supported
  (BJL-50).
- TLS data connections end with just `close_notify`. Java's close of a TLS 1.3 socket sends a
  `user_canceled` alert first, which GnuTLS clients such as FileZilla and lftp report as a fatal error
  on every listing and download (BJL-2).
- Replies, and the session's transfer threads, use locks instead of `synchronized`, so a session on a
  virtual thread doesn't hold on to its carrier thread while a reply waits for a slow client (Java
  21-23, BJL-52). Transfer threads are started and managed by `bjl_net_framework`'s server (BJL-60).

### Client

- Passive mode connects to the control connection's host and ignores the address in the PASV reply by
  default, like curl's `--ftp-skip-pasv-ip`: servers behind NAT often send a private address.
- Active mode listens before sending `PORT` / `EPRT` (servers may connect as soon as they get it), and
  it used to send `PORT` with no address, which servers reject. `EPRT` is used for IPv6 (RFC 2428).
- Data connections resume the control connection's TLS session (BJL-18), which servers such as vsftpd
  (`require_ssl_reuse`) and FileZilla Server require. In active mode TLS starts after the `1xx` reply, so
  a refused session can be retried (BJL-28), and a `522` reply after the handshake is handled.
- Passive mode connects the data connection before sending the transfer command: servers such as vsftpd
  wait for it before they send the `1xx` reply.
- `TYPE` is sent only when it changes, not before every transfer, one round trip less per file (BJL-34).
- If the client loses track of where the control connection is in the conversation, it drops the
  connection, so the next command reconnects instead of reading the wrong reply.
- Listing parsing: `MLSx` entries are split into facts and path name as RFC 3659 section 7.2 says (a
  path name may contain spaces or `;`, a fact a space), names with non-ASCII owners or groups are no longer
  garbled, and address octets over 127 are no longer negative.
- Commands and data connections use locks instead of `synchronized`, so many clients used from virtual
  threads don't stall on Java 21-23 (BJL-58).
- `ListEntry`: the LIST / MLSx entry parsing, public and shared with `bjl_file_system_ftp`'s `FtpFile`, which
  had its own copy (with the old non-ASCII bug). `FtpClientFile.splitMlsxEntry` and `mlsxName` are
  deprecated pass-throughs to it.
- `getLastActivity()` was always 0 (its field was never updated); it now says when the control
  connection was last used.

### Performance

- Directory listings go out through one 64 KB buffer that is flushed once (BJL-30), and multi-line
  replies such as `FEAT` are sent with one flush (BJL-35), not one TCP segment or TLS record per line.
- Link targets allowed by the server are resolved once per session, not for every file checked, such as
  every entry of an `MLSD` listing (BJL-32).
- Socket buffers are sized by the operating system (TCP auto tuning) unless a size is configured, and are
  set before connecting so window scaling applies (BJL-29).

### Changed (may need a code change)

- An FTPS client connecting to a server whose certificate doesn't match the host name now fails; use
  `setVerifyHostname(false)` for such a server.
- An FTPS client whose server accepts AUTH TLS but whose TLS fails gets an `IOException` from `connect()`
  instead of continuing without TLS.
- `MLSx` and `MDTM` times are UTC, not the server's local time.
