## Play async
Playground for core.async - Built on [core.async tutorial](http://www.braveclojure.com/core-async/)

### Sample Channel
The implementation consists of a channel pattern that can be used to persist log
messages or cache. It implements two knobs that can be used to persist the
messages using a given function at the said time interval or when the number of
messages captured are more than a given threshold.

The implementation also has a shutdown hook to cleanup and persist any pending
messages based on the given function. It accepts three implementations that
can be used- ``on-timeout``, ``on-overflow`` and ``on-close``. Modify
``process`` function to change these as per your requirement.

### Run
Execute ``lein run`` to create a channel and publish 10 messages. Additionally,
you can send following parameters as EDN string-

* ``:buffer``: Sets the sliding buffer size of the channel
* ``:poll``: Channel timeout in milliseconds. Default is ``100``
* ``:timeout``: Interval in milliseconds at which the collected messages are
  acted upon. Default is ``5000``
* ``:threshold``: Overflow threshold to collect the messages to act upon.
  Default is ``5``
* ``:payload``: No. of messages to publish
* ``:breathe``: Sleep time in milliseconds for channel thread to breathe

For example-

```
lein run "{:threshold 100 :payload 100}"
```
