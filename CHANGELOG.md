# Changelog

## v0.2.3

This release is a simple upgrade from fs2 0.10.0-M5 to 0.10.0-M6

* [#32](https://github.com/zainab-ali/fs2-reactive-streams/pull/32): Upgrade fs2 to 0.10.0-M6 by @aeons

## v0.2.2

This release bugfixes `StreamSubscription.cancel` in a multi-threaded `ExecutionContext`.

This would have remained lethally undetected if not for **@aeons**, who switched the tests to use the global `ExecutionContext`.  Thanks much!

* [#28](https://github.com/zainab-ali/fs2-reactive-streams/pull/28): Use the global `ExecutionContext` for all tests.  by @aeons
* [#30](https://github.com/zainab-ali/fs2-reactive-streams/pull/30): Fix bug in `StreamSubscription` by adding a flag for synchronous execution of `cancel`.  by @zainab-ali

## v0.2.1

**_This version is also broken.  Please use v0.2.2 instead!_**

This release bugfixes the `StreamUnicastPublisher` in a multi-threaded `ExecutionContext`.  It also upgrades the fs2 dependency to 0.10.0-M5.

This is entirely the hard work of **@rossabaker**, who found and fixed the issues.  Thank you!

 * [#26](https://github.com/zainab-ali/fs2-reactive-streams/pull/26): Make `StreamUnicastPublisher` work with multiple threads. by @rossabaker

## v0.2.0

**_This version is broken.  Please use v0.2.2 instead!_**

This release upgrades fs2 from 0.9 to 0.10.0-M2.  fs2's `Task` has been replaced with `IO` from cats-effect.
Thank you **@aeons** for doing the heavy lifting!

 * [#25](https://github.com/zainab-ali/fs2-reactive-streams/pull/25): updated dependency to fs2-0.10.0 and cats-effect. by @aeons

## v0.1.1

This release backports the bugfix to `StreamSubscription.cancel` in a multi-threaded `ExecutionContext`.

* [#31](https://github.com/zainab-ali/fs2-reactive-streams/pull/31): Backported bugfix in `StreamSubscription` by adding a flag for synchronous execution of `cancel`.  by @zainab-ali


## v0.1.0

**_This version is broken.  Please use v0.1.1 instead!_**

First release!
