# -*- coding: utf-8 -*-
#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

import functools
import itertools
import os
import platform
import re
import sys
import threading
import traceback
import typing
from types import TracebackType
from typing import Any, Callable, IO, Iterator, List, Optional, TextIO, Tuple, Union

from pyspark.errors import PySparkRuntimeError

from py4j.clientserver import ClientServer

__all__: List[str] = []

from py4j.java_gateway import JavaObject

if typing.TYPE_CHECKING:
    from pyspark.sql import SparkSession


def print_exec(stream: TextIO) -> None:
    ei = sys.exc_info()
    traceback.print_exception(ei[0], ei[1], ei[2], None, stream)


class VersionUtils:
    """
    Provides utility method to determine Spark versions with given input string.
    """

    @staticmethod
    def majorMinorVersion(sparkVersion: str) -> Tuple[int, int]:
        """
        Given a Spark version string, return the (major version number, minor version number).
        E.g., for 2.0.1-SNAPSHOT, return (2, 0).

        Examples
        --------
        >>> sparkVersion = "2.4.0"
        >>> VersionUtils.majorMinorVersion(sparkVersion)
        (2, 4)
        >>> sparkVersion = "2.3.0-SNAPSHOT"
        >>> VersionUtils.majorMinorVersion(sparkVersion)
        (2, 3)
        """
        m = re.search(r"^(\d+)\.(\d+)(\..*)?$", sparkVersion)
        if m is not None:
            return (int(m.group(1)), int(m.group(2)))
        else:
            raise ValueError(
                "Spark tried to parse '%s' as a Spark" % sparkVersion
                + " version string, but it could not find the major and minor"
                + " version numbers."
            )


def fail_on_stopiteration(f: Callable) -> Callable:
    """
    Wraps the input function to fail on 'StopIteration' by raising a 'RuntimeError'
    prevents silent loss of data when 'f' is used in a for loop in Spark code
    """

    def wrapper(*args: Any, **kwargs: Any) -> Any:
        try:
            return f(*args, **kwargs)
        except StopIteration as exc:
            raise PySparkRuntimeError(
                error_class="STOP_ITERATION_OCCURRED",
                message_parameters={
                    "exc": str(exc),
                },
            )

    return wrapper


def walk_tb(tb: Optional[TracebackType]) -> Iterator[TracebackType]:
    while tb is not None:
        yield tb
        tb = tb.tb_next


def try_simplify_traceback(tb: TracebackType) -> Optional[TracebackType]:
    """
    Simplify the traceback. It removes the tracebacks in the current package, and only
    shows the traceback that is related to the thirdparty and user-specified codes.

    Returns
    -------
    TracebackType or None
      Simplified traceback instance. It returns None if it fails to simplify.

    Notes
    -----
    This keeps the tracebacks once it sees they are from a different file even
    though the following tracebacks are from the current package.

    Examples
    --------
    >>> import importlib
    >>> import sys
    >>> import traceback
    >>> import tempfile
    >>> with tempfile.TemporaryDirectory() as tmp_dir:
    ...     with open("%s/dummy_module.py" % tmp_dir, "w") as f:
    ...         _ = f.write(
    ...             'def raise_stop_iteration():\\n'
    ...             '    raise StopIteration()\\n\\n'
    ...             'def simple_wrapper(f):\\n'
    ...             '    def wrapper(*a, **k):\\n'
    ...             '        return f(*a, **k)\\n'
    ...             '    return wrapper\\n')
    ...         f.flush()
    ...         spec = importlib.util.spec_from_file_location(
    ...             "dummy_module", "%s/dummy_module.py" % tmp_dir)
    ...         dummy_module = importlib.util.module_from_spec(spec)
    ...         spec.loader.exec_module(dummy_module)
    >>> def skip_doctest_traceback(tb):
    ...     import pyspark
    ...     root = os.path.dirname(pyspark.__file__)
    ...     pairs = zip(walk_tb(tb), traceback.extract_tb(tb))
    ...     for cur_tb, cur_frame in pairs:
    ...         if cur_frame.filename.startswith(root):
    ...             return cur_tb

    Regular exceptions should show the file name of the current package as below.

    >>> exc_info = None
    >>> try:
    ...     fail_on_stopiteration(dummy_module.raise_stop_iteration)()
    ... except Exception as e:
    ...     tb = sys.exc_info()[-1]
    ...     e.__cause__ = None
    ...     exc_info = "".join(
    ...         traceback.format_exception(type(e), e, tb))
    >>> print(exc_info)  # doctest: +NORMALIZE_WHITESPACE, +ELLIPSIS
    Traceback (most recent call last):
      File ...
        ...
      File "/.../pyspark/util.py", line ...
        ...
    pyspark.errors.exceptions.base.PySparkRuntimeError: ...
    >>> "pyspark/util.py" in exc_info
    True

    If the traceback is simplified with this method, it hides the current package file name:

    >>> exc_info = None
    >>> try:
    ...     fail_on_stopiteration(dummy_module.raise_stop_iteration)()
    ... except Exception as e:
    ...     tb = try_simplify_traceback(sys.exc_info()[-1])
    ...     e.__cause__ = None
    ...     exc_info = "".join(
    ...         traceback.format_exception(
    ...             type(e), e, try_simplify_traceback(skip_doctest_traceback(tb))))
    >>> print(exc_info)  # doctest: +NORMALIZE_WHITESPACE, +ELLIPSIS
    pyspark.errors.exceptions.base.PySparkRuntimeError: ...
    >>> "pyspark/util.py" in exc_info
    False

    In the case below, the traceback contains the current package in the middle.
    In this case, it just hides the top occurrence only.

    >>> exc_info = None
    >>> try:
    ...     fail_on_stopiteration(dummy_module.simple_wrapper(
    ...         fail_on_stopiteration(dummy_module.raise_stop_iteration)))()
    ... except Exception as e:
    ...     tb = sys.exc_info()[-1]
    ...     e.__cause__ = None
    ...     exc_info_a = "".join(
    ...         traceback.format_exception(type(e), e, tb))
    ...     exc_info_b = "".join(
    ...         traceback.format_exception(
    ...             type(e), e, try_simplify_traceback(skip_doctest_traceback(tb))))
    >>> exc_info_a.count("pyspark/util.py")
    2
    >>> exc_info_b.count("pyspark/util.py")
    1
    """
    if "pypy" in platform.python_implementation().lower():
        # Traceback modification is not supported with PyPy in PySpark.
        return None
    if sys.version_info[:2] < (3, 7):
        # Traceback creation is not supported Python < 3.7.
        # See https://bugs.python.org/issue30579.
        return None

    import pyspark

    root = os.path.dirname(pyspark.__file__)
    tb_next = None
    new_tb = None
    pairs = zip(walk_tb(tb), traceback.extract_tb(tb))
    last_seen = []

    for cur_tb, cur_frame in pairs:
        if not cur_frame.filename.startswith(root):
            # Filter the stacktrace from the PySpark source itself.
            last_seen = [(cur_tb, cur_frame)]
            break

    for cur_tb, cur_frame in reversed(list(itertools.chain(last_seen, pairs))):
        # Once we have seen the file names outside, don't skip.
        new_tb = TracebackType(
            tb_next=tb_next,
            tb_frame=cur_tb.tb_frame,
            tb_lasti=cur_tb.tb_frame.f_lasti,
            tb_lineno=cur_tb.tb_frame.f_lineno if cur_tb.tb_frame.f_lineno is not None else -1,
        )
        tb_next = new_tb
    return new_tb


def _print_missing_jar(lib_name: str, pkg_name: str, jar_name: str, spark_version: str) -> None:
    print(
        """
________________________________________________________________________________________________

  Spark %(lib_name)s libraries not found in class path. Try one of the following.

  1. Include the %(lib_name)s library and its dependencies with in the
     spark-submit command as

     $ bin/spark-submit --packages org.apache.spark:spark-%(pkg_name)s:%(spark_version)s ...

  2. Download the JAR of the artifact from Maven Central http://search.maven.org/,
     Group Id = org.apache.spark, Artifact Id = spark-%(jar_name)s, Version = %(spark_version)s.
     Then, include the jar in the spark-submit command as

     $ bin/spark-submit --jars <spark-%(jar_name)s.jar> ...

________________________________________________________________________________________________

"""
        % {
            "lib_name": lib_name,
            "pkg_name": pkg_name,
            "jar_name": jar_name,
            "spark_version": spark_version,
        }
    )


def _parse_memory(s: str) -> int:
    """
    Parse a memory string in the format supported by Java (e.g. 1g, 200m) and
    return the value in MiB

    Examples
    --------
    >>> _parse_memory("256m")
    256
    >>> _parse_memory("2g")
    2048
    """
    units = {"g": 1024, "m": 1, "t": 1 << 20, "k": 1.0 / 1024}
    if s[-1].lower() not in units:
        raise ValueError("invalid format: " + s)
    return int(float(s[:-1]) * units[s[-1].lower()])


def inheritable_thread_target(f: Optional[Union[Callable, "SparkSession"]] = None) -> Callable:
    """
    Return thread target wrapper which is recommended to be used in PySpark when the
    pinned thread mode is enabled. The wrapper function, before calling original
    thread target, it inherits the inheritable properties specific
    to JVM thread such as ``InheritableThreadLocal``, or thread local such as tags
    with Spark Connect.

    When the pinned thread mode is off, it return the original ``f``.

    .. versionadded:: 3.2.0

    .. versionchanged:: 3.5.0
        Supports Spark Connect.

    Parameters
    ----------
    f : function, or :class:`SparkSession`
        the original thread target, or :class:`SparkSession` if Spark Connect is being used.
        See the examples below.

    Notes
    -----
    This API is experimental.

    It is important to know that it captures the local properties or tags when you
    decorate it whereas :class:`InheritableThread` captures when the thread is started.
    Therefore, it is encouraged to decorate it when you want to capture the local
    properties.

    For example, the local properties or tags from the current Spark context or Spark
    session is captured when you define a function here instead of the invocation:

    >>> @inheritable_thread_target
    ... def target_func():
    ...     pass  # your codes.

    If you have any updates on local properties or tags afterwards, it would not be
    reflected to the Spark context in ``target_func()``.

    The example below mimics the behavior of JVM threads as close as possible:

    >>> Thread(target=inheritable_thread_target(target_func)).start()  # doctest: +SKIP

    If you're using Spark Connect, you should explicitly provide Spark session as follows:

    >>> @inheritable_thread_target(session)  # doctest: +SKIP
    ... def target_func():
    ...     pass  # your codes.

    >>> Thread(target=inheritable_thread_target(session)(target_func)).start()  # doctest: +SKIP
    """
    from pyspark.sql import is_remote

    # Spark Connect
    if is_remote():
        session = f
        assert session is not None, "Spark Connect session must be provided."

        def outer(ff: Callable) -> Callable:
            if not hasattr(session.client.thread_local, "tags"):  # type: ignore[union-attr]
                session.client.thread_local.tags = set()  # type: ignore[union-attr]
            tags = set(session.client.thread_local.tags)  # type: ignore[union-attr]

            @functools.wraps(ff)
            def inner(*args: Any, **kwargs: Any) -> Any:
                # Set tags in child thread.
                session.client.thread_local.tags = tags  # type: ignore[union-attr]
                return ff(*args, **kwargs)

            return inner

        return outer

    # Non Spark Connect
    from pyspark import SparkContext

    if isinstance(SparkContext._gateway, ClientServer):
        # Here's when the pinned-thread mode (PYSPARK_PIN_THREAD) is on.

        # NOTICE the internal difference vs `InheritableThread`. `InheritableThread`
        # copies local properties when the thread starts but `inheritable_thread_target`
        # copies when the function is wrapped.
        assert SparkContext._active_spark_context is not None
        properties = SparkContext._active_spark_context._jsc.sc().getLocalProperties().clone()
        assert callable(f)

        @functools.wraps(f)
        def wrapped(*args: Any, **kwargs: Any) -> Any:
            # Set local properties in child thread.
            assert SparkContext._active_spark_context is not None
            SparkContext._active_spark_context._jsc.sc().setLocalProperties(properties)
            return f(*args, **kwargs)  # type: ignore[misc, operator]

        return wrapped
    else:
        return f  # type: ignore[return-value]


def handle_worker_exception(e: BaseException, outfile: IO) -> None:
    """
    Handles exception for Python worker which writes SpecialLengths.PYTHON_EXCEPTION_THROWN (-2)
    and exception traceback info to outfile. JVM could then read from the outfile and perform
    exception handling there.
    """
    from pyspark.serializers import write_int, write_with_length, SpecialLengths

    try:
        exc_info = None
        if os.environ.get("SPARK_SIMPLIFIED_TRACEBACK", False):
            tb = try_simplify_traceback(sys.exc_info()[-1])  # type: ignore[arg-type]
            if tb is not None:
                e.__cause__ = None
                exc_info = "".join(traceback.format_exception(type(e), e, tb))
        if exc_info is None:
            exc_info = traceback.format_exc()

        write_int(SpecialLengths.PYTHON_EXCEPTION_THROWN, outfile)
        write_with_length(exc_info.encode("utf-8"), outfile)
    except IOError:
        # JVM close the socket
        pass
    except BaseException:
        # Write the error to stderr if it happened while serializing
        print("PySpark worker failed with exception:", file=sys.stderr)
        print(traceback.format_exc(), file=sys.stderr)


class InheritableThread(threading.Thread):
    """
    Thread that is recommended to be used in PySpark when the pinned thread mode is
    enabled. The wrapper function, before calling original thread target, it
    inherits the inheritable properties specific to JVM thread such as
    ``InheritableThreadLocal``, or thread local such as tags
    with Spark Connect.

    When the pinned thread mode is off, this works as :class:`threading.Thread`.

    .. versionadded:: 3.1.0

    .. versionchanged:: 3.5.0
        Supports Spark Connect.

    Notes
    -----
    This API is experimental.
    """

    _props: JavaObject

    def __init__(
        self, target: Callable, *args: Any, session: Optional["SparkSession"] = None, **kwargs: Any
    ):
        from pyspark.sql import is_remote

        # Spark Connect
        if is_remote():
            assert session is not None, "Spark Connect must be provided."
            self._session = session

            def copy_local_properties(*a: Any, **k: Any) -> Any:
                # Set tags in child thread.
                assert hasattr(self, "_tags")
                session.client.thread_local.tags = self._tags  # type: ignore[union-attr, has-type]
                return target(*a, **k)

            super(InheritableThread, self).__init__(
                target=copy_local_properties, *args, **kwargs  # type: ignore[misc]
            )
        else:
            # Non Spark Connect
            from pyspark import SparkContext

            if isinstance(SparkContext._gateway, ClientServer):
                # Here's when the pinned-thread mode (PYSPARK_PIN_THREAD) is on.
                def copy_local_properties(*a: Any, **k: Any) -> Any:
                    # self._props is set before starting the thread to match the behavior with JVM.
                    assert hasattr(self, "_props")
                    assert SparkContext._active_spark_context is not None
                    SparkContext._active_spark_context._jsc.sc().setLocalProperties(self._props)
                    return target(*a, **k)

                super(InheritableThread, self).__init__(
                    target=copy_local_properties, *args, **kwargs  # type: ignore[misc]
                )
            else:
                super(InheritableThread, self).__init__(
                    target=target, *args, **kwargs  # type: ignore[misc]
                )

    def start(self) -> None:
        from pyspark.sql import is_remote

        if is_remote():
            # Spark Connect
            assert hasattr(self, "_session")
            if not hasattr(self._session.client.thread_local, "tags"):
                self._session.client.thread_local.tags = set()
            self._tags = set(self._session.client.thread_local.tags)
        else:
            # Non Spark Connect
            from pyspark import SparkContext

            if isinstance(SparkContext._gateway, ClientServer):
                # Here's when the pinned-thread mode (PYSPARK_PIN_THREAD) is on.

                # Local property copy should happen in Thread.start to mimic JVM's behavior.
                assert SparkContext._active_spark_context is not None
                self._props = (
                    SparkContext._active_spark_context._jsc.sc().getLocalProperties().clone()
                )
        return super(InheritableThread, self).start()


if __name__ == "__main__":
    if "pypy" not in platform.python_implementation().lower() and sys.version_info[:2] >= (3, 7):
        import doctest
        import pyspark.util
        from pyspark.context import SparkContext

        globs = pyspark.util.__dict__.copy()
        globs["sc"] = SparkContext("local[4]", "PythonTest")
        (failure_count, test_count) = doctest.testmod(pyspark.util, globs=globs)
        globs["sc"].stop()

        if failure_count:
            sys.exit(-1)
