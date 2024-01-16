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
from abc import ABC, abstractmethod
from collections import UserDict
from typing import Any, Dict, Iterator, List, Sequence, Tuple, Type, Union, TYPE_CHECKING

from pyspark.sql import Row
from pyspark.sql.types import StructType
from pyspark.errors import PySparkNotImplementedError

if TYPE_CHECKING:
    from pyspark.sql.session import SparkSession


__all__ = [
    "DataSource",
    "DataSourceReader",
    "DataSourceWriter",
    "DataSourceRegistration",
    "InputPartition",
    "WriterCommitMessage",
]


class DataSource(ABC):
    """
    A base class for data sources.

    This class represents a custom data source that allows for reading from and/or
    writing to it. The data source provides methods to create readers and writers
    for reading and writing data, respectively. At least one of the methods ``reader``
    or ``writer`` must be implemented by any subclass to make the data source either
    readable or writable (or both).

    After implementing this interface, you can start to load your data source using
    ``spark.read.format(...).load()`` and save data using ``df.write.format(...).save()``.

    .. versionadded: 4.0.0
    """

    def __init__(self, options: Dict[str, str]) -> None:
        """
        Initializes the data source with user-provided options.

        Parameters
        ----------
        options : dict
            A case-insensitive dictionary representing the options for this data source.

        Notes
        -----
        This method should not be overridden.
        """
        self.options = options

    @classmethod
    def name(cls) -> str:
        """
        Returns a string represents the format name of this data source.

        By default, it is the class name of the data source. It can be overridden to
        provide a customized short name for the data source.

        Examples
        --------
        >>> def name(cls):
        ...     return "my_data_source"
        """
        return cls.__name__

    def schema(self) -> Union[StructType, str]:
        """
        Returns the schema of the data source.

        It can refer any field initialized in the ``__init__`` method to infer the
        data source's schema when users do not explicitly specify it. This method is
        invoked once when calling ``spark.read.format(...).load()`` to get the schema
        for a data source read operation. If this method is not implemented, and a
        user does not provide a schema when reading the data source, an exception will
        be thrown.

        Returns
        -------
        schema : StructType or str
            The schema of this data source or a DDL string represents the schema

        Examples
        --------
        Returns a DDL string:

        >>> def schema(self):
        ...    return "a INT, b STRING"

        Returns a StructType:

        >>> def schema(self):
        ...   return StructType().add("a", "int").add("b", "string")
        """
        raise PySparkNotImplementedError(
            error_class="NOT_IMPLEMENTED",
            message_parameters={"feature": "schema"},
        )

    def reader(self, schema: StructType) -> "DataSourceReader":
        """
        Returns a ``DataSourceReader`` instance for reading data.

        The implementation is required for readable data sources.

        Parameters
        ----------
        schema : StructType
            The schema of the data to be read.

        Returns
        -------
        reader : DataSourceReader
            A reader instance for this data source.
        """
        raise PySparkNotImplementedError(
            error_class="NOT_IMPLEMENTED",
            message_parameters={"feature": "reader"},
        )

    def writer(self, schema: StructType, overwrite: bool) -> "DataSourceWriter":
        """
        Returns a ``DataSourceWriter`` instance for writing data.

        The implementation is required for writable data sources.

        Parameters
        ----------
        schema : StructType
            The schema of the data to be written.
        overwrite : bool
            A flag indicating whether to overwrite existing data when writing to the data source.

        Returns
        -------
        writer : DataSourceWriter
            A writer instance for this data source.
        """
        raise PySparkNotImplementedError(
            error_class="NOT_IMPLEMENTED",
            message_parameters={"feature": "writer"},
        )


class InputPartition:
    """
    A base class representing an input partition returned by the `partitions()`
    method of `DataSourceReader`.

    .. versionadded: 4.0.0

    Notes
    -----
    This class must be picklable.

    Examples
    --------
    Use the default input partition implementation:

    >>> def partitions(self):
    ...     return [InputPartition(1)]

    Subclass the input partition class:

    >>> from dataclasses import dataclass
    >>> @dataclass
    ... class RangeInputPartition(InputPartition):
    ...     start: int
    ...     end: int

    >>> def partitions(self):
    ...     return [RangeInputPartition(1, 3), RangeInputPartition(4, 6)]
    """

    def __init__(self, value: Any) -> None:
        self.value = value

    def __repr__(self) -> str:
        attributes = ", ".join([f"{k}={v!r}" for k, v in self.__dict__.items()])
        return f"{self.__class__.__name__}({attributes})"


class DataSourceReader(ABC):
    """
    A base class for data source readers. Data source readers are responsible for
    outputting data from a data source.

    .. versionadded: 4.0.0
    """

    def partitions(self) -> Sequence[InputPartition]:
        """
        Returns an iterator of partitions for this data source.

        Partitions are used to split data reading operations into parallel tasks.
        If this method returns N partitions, the query planner will create N tasks.
        Each task will execute ``read(partition)`` in parallel, using the respective
        partition value to read the data.

        This method is called once during query planning. By default, it returns a
        single partition with the value ``None``. Subclasses can override this method
        to return multiple partitions.

        It's recommended to override this method for better performance when reading
        large datasets.

        Returns
        -------
        Sequence[InputPartition]
            A sequence of partitions for this data source. Each partition value
            must be an instance of `InputPartition` or a subclass of it.

        Notes
        -----
        All partition values must be picklable objects.

        Examples
        --------
        Returns a list of integers:

        >>> def partitions(self):
        ...     return [InputPartition(1), InputPartition(2), InputPartition(3)]

        Returns a list of string:

        >>> def partitions(self):
        ...     return [InputPartition("a"), InputPartition("b"), InputPartition("c")]

        Returns a list of ranges:

        >>> class RangeInputPartition(InputPartition):
        ...    def __init__(self, start, end):
        ...        self.start = start
        ...        self.end = end

        >>> def partitions(self):
        ...     return [RangeInputPartition(1, 3), RangeInputPartition(5, 10)]
        """
        raise PySparkNotImplementedError(
            error_class="NOT_IMPLEMENTED",
            message_parameters={"feature": "partitions"},
        )

    @abstractmethod
    def read(self, partition: InputPartition) -> Iterator[Union[Tuple, Row]]:
        """
        Generates data for a given partition and returns an iterator of tuples or rows.

        This method is invoked once per partition to read the data. Implementing
        this method is required for readable data sources. You can initialize any
        non-serializable resources required for reading data from the data source
        within this method.

        Parameters
        ----------
        partition : object
            The partition to read. It must be one of the partition values returned by
            ``partitions()``.

        Returns
        -------
        Iterator[Tuple] or Iterator[Row]
            An iterator of tuples or rows. Each tuple or row will be converted to a row
            in the final DataFrame.

        Examples
        --------
        Yields a list of tuples:

        >>> def read(self, partition: InputPartition):
        ...     yield (partition.value, 0)
        ...     yield (partition.value, 1)

        Yields a list of rows:

        >>> def read(self, partition: InputPartition):
        ...     yield Row(partition=partition.value, value=0)
        ...     yield Row(partition=partition.value, value=1)
        """
        ...


class DataSourceWriter(ABC):
    """
    A base class for data source writers. Data source writers are responsible for saving
    the data to the data source.

    .. versionadded: 4.0.0
    """

    @abstractmethod
    def write(self, iterator: Iterator[Row]) -> "WriterCommitMessage":
        """
        Writes data into the data source.

        This method is called once on each executor to write data to the data source.
        It accepts an iterator of input data and returns a single row representing a
        commit message, or None if there is no commit message.

        The driver collects commit messages, if any, from all executors and passes them
        to the ``commit`` method if all tasks run successfully. If any task fails, the
        ``abort`` method will be called with the collected commit messages.

        Parameters
        ----------
        iterator : Iterator[Row]
            An iterator of input data.

        Returns
        -------
        WriterCommitMessage : a serializable commit message
        """
        ...

    def commit(self, messages: List["WriterCommitMessage"]) -> None:
        """
        Commits this writing job with a list of commit messages.

        This method is invoked on the driver when all tasks run successfully. The
        commit messages are collected from the ``write`` method call from each task,
        and are passed to this method. The implementation should use the commit messages
        to commit the writing job to the data source.

        Parameters
        ----------
        messages : List[WriterCommitMessage]
            A list of commit messages.
        """
        ...

    def abort(self, messages: List["WriterCommitMessage"]) -> None:
        """
        Aborts this writing job due to task failures.

        This method is invoked on the driver when one or more tasks failed. The commit
        messages are collected from the ``write`` method call from each task, and are
        passed to this method. The implementation should use the commit messages to
        abort the writing job to the data source.

        Parameters
        ----------
        messages : List[WriterCommitMessage]
            A list of commit messages.
        """
        ...


class WriterCommitMessage:
    """
    A commit message returned by the ``write`` method of ``DataSourceWriter`` and will be
    sent back to the driver side as input parameter of ``commit`` or ``abort`` method.

    .. versionadded: 4.0.0

    Notes
    -----
    This class must be picklable.
    """

    ...


class DataSourceRegistration:
    """
    Wrapper for data source registration. This instance can be accessed by
    :attr:`spark.dataSource`.

    .. versionadded: 4.0.0
    """

    def __init__(self, sparkSession: "SparkSession"):
        self.sparkSession = sparkSession

    def register(
        self,
        dataSource: Type["DataSource"],
    ) -> None:
        """Register a Python user-defined data source.

        Parameters
        ----------
        dataSource : type
            The data source class to be registered. It should be a subclass of DataSource.
        """
        from pyspark.sql.udf import _wrap_function

        name = dataSource.name()
        sc = self.sparkSession.sparkContext
        # Serialize the data source class.
        wrapped = _wrap_function(sc, dataSource)
        assert sc._jvm is not None
        jvm = sc._jvm
        ds = jvm.org.apache.spark.sql.execution.datasources.v2.python.UserDefinedPythonDataSource(
            wrapped
        )
        self.sparkSession._jsparkSession.dataSource().registerPython(name, ds)


class CaseInsensitiveDict(UserDict):
    """
    A case-insensitive map of string keys to values.

    This is used by Python data source options to ensure consistent case insensitivity.
    """

    def __init__(self, *args: Any, **kwargs: Any) -> None:
        super().__init__(*args, **kwargs)
        self.update(*args, **kwargs)

    def __setitem__(self, key: str, value: Any) -> None:
        super().__setitem__(key.lower(), value)

    def __getitem__(self, key: str) -> Any:
        return super().__getitem__(key.lower())

    def __delitem__(self, key: str) -> None:
        super().__delitem__(key.lower())

    def __contains__(self, key: object) -> bool:
        if isinstance(key, str):
            return super().__contains__(key.lower())
        return False

    def update(self, *args: Any, **kwargs: Any) -> None:
        for k, v in dict(*args, **kwargs).items():
            self[k] = v

    def copy(self) -> "CaseInsensitiveDict":
        return type(self)(self)
