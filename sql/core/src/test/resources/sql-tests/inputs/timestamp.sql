-- timestamp literals, functions and operations

select timestamp '2019-01-01\t';
select timestamp '2019-01-01中文';

-- invalid: year too large
select timestamp'4294967297';
-- invalid: minute field can have at most 2 digits
select timestamp'2021-01-01T12:30:4294967297.123456';

select current_timestamp = current_timestamp;
-- under ANSI mode, `current_timestamp` can't be a function name.
select current_timestamp() = current_timestamp();

select localtimestamp() = localtimestamp();

-- timestamp numeric fields constructor
SELECT make_timestamp(2021, 07, 11, 6, 30, 45.678);
SELECT make_timestamp(2021, 07, 11, 6, 30, 45.678, 'CET');
SELECT make_timestamp(2021, 07, 11, 6, 30, 60.007);

-- [SPARK-31710] TIMESTAMP_SECONDS, TIMESTAMP_MILLISECONDS and TIMESTAMP_MICROSECONDS that always create timestamp_ltz
select TIMESTAMP_SECONDS(1230219000),TIMESTAMP_SECONDS(-1230219000),TIMESTAMP_SECONDS(null);
select TIMESTAMP_SECONDS(1.23), TIMESTAMP_SECONDS(1.23d), TIMESTAMP_SECONDS(FLOAT(1.23));
select TIMESTAMP_MILLIS(1230219000123),TIMESTAMP_MILLIS(-1230219000123),TIMESTAMP_MILLIS(null);
select TIMESTAMP_MICROS(1230219000123123),TIMESTAMP_MICROS(-1230219000123123),TIMESTAMP_MICROS(null);
-- overflow exception
select TIMESTAMP_SECONDS(1230219000123123);
select TIMESTAMP_SECONDS(-1230219000123123);
select TIMESTAMP_MILLIS(92233720368547758);
select TIMESTAMP_MILLIS(-92233720368547758);
-- truncate exception
select TIMESTAMP_SECONDS(0.1234567);
-- truncation is OK for float/double
select TIMESTAMP_SECONDS(0.1234567d), TIMESTAMP_SECONDS(FLOAT(0.1234567));

-- [SPARK-22333]: timeFunctionCall has conflicts with columnReference
create temporary view ttf1 as select * from values
  (1, 2),
  (2, 3)
  as ttf1(`current_date`, `current_timestamp`);
select typeof(current_date), typeof(current_timestamp) from ttf1;

create temporary view ttf2 as select * from values
  (1, 2),
  (2, 3)
  as ttf2(a, b);
select current_date = current_date(), current_timestamp = current_timestamp(), a, b from ttf2;
select a, b from ttf2 order by a, current_date;


-- UNIX_SECONDS, UNIX_MILLISECONDS and UNIX_MICROSECONDS
select UNIX_SECONDS(timestamp'2020-12-01 14:30:08Z'), UNIX_SECONDS(timestamp'2020-12-01 14:30:08.999999Z'), UNIX_SECONDS(null);
select UNIX_MILLIS(timestamp'2020-12-01 14:30:08Z'), UNIX_MILLIS(timestamp'2020-12-01 14:30:08.999999Z'), UNIX_MILLIS(null);
select UNIX_MICROS(timestamp'2020-12-01 14:30:08Z'), UNIX_MICROS(timestamp'2020-12-01 14:30:08.999999Z'), UNIX_MICROS(null);

select to_timestamp(null), to_timestamp('2016-12-31 00:12:00'), to_timestamp('2016-12-31', 'yyyy-MM-dd');
-- variable-length second fraction tests
select to_timestamp('2019-10-06 10:11:12.', 'yyyy-MM-dd HH:mm:ss.SSSSSS[zzz]');
select to_timestamp('2019-10-06 10:11:12.0', 'yyyy-MM-dd HH:mm:ss.SSSSSS[zzz]');
select to_timestamp('2019-10-06 10:11:12.1', 'yyyy-MM-dd HH:mm:ss.SSSSSS[zzz]');
select to_timestamp('2019-10-06 10:11:12.12', 'yyyy-MM-dd HH:mm:ss.SSSSSS[zzz]');
select to_timestamp('2019-10-06 10:11:12.123UTC', 'yyyy-MM-dd HH:mm:ss.SSSSSS[zzz]');
select to_timestamp('2019-10-06 10:11:12.1234', 'yyyy-MM-dd HH:mm:ss.SSSSSS[zzz]');
select to_timestamp('2019-10-06 10:11:12.12345CST', 'yyyy-MM-dd HH:mm:ss.SSSSSS[zzz]');
select to_timestamp('2019-10-06 10:11:12.123456PST', 'yyyy-MM-dd HH:mm:ss.SSSSSS[zzz]');
-- second fraction exceeded max variable length
select to_timestamp('2019-10-06 10:11:12.1234567PST', 'yyyy-MM-dd HH:mm:ss.SSSSSS[zzz]');
-- special cases
select to_timestamp('123456 2019-10-06 10:11:12.123456PST', 'SSSSSS yyyy-MM-dd HH:mm:ss.SSSSSS[zzz]');
select to_timestamp('223456 2019-10-06 10:11:12.123456PST', 'SSSSSS yyyy-MM-dd HH:mm:ss.SSSSSS[zzz]');
select to_timestamp('2019-10-06 10:11:12.1234', 'yyyy-MM-dd HH:mm:ss.[SSSSSS]');
select to_timestamp('2019-10-06 10:11:12.123', 'yyyy-MM-dd HH:mm:ss[.SSSSSS]');
select to_timestamp('2019-10-06 10:11:12', 'yyyy-MM-dd HH:mm:ss[.SSSSSS]');
select to_timestamp('2019-10-06 10:11:12.12', 'yyyy-MM-dd HH:mm[:ss.SSSSSS]');
select to_timestamp('2019-10-06 10:11', 'yyyy-MM-dd HH:mm[:ss.SSSSSS]');
select to_timestamp("2019-10-06S10:11:12.12345", "yyyy-MM-dd'S'HH:mm:ss.SSSSSS");
select to_timestamp("12.12342019-10-06S10:11", "ss.SSSSyyyy-MM-dd'S'HH:mm");
select to_timestamp("12.1232019-10-06S10:11", "ss.SSSSyyyy-MM-dd'S'HH:mm");
select to_timestamp("12.1232019-10-06S10:11", "ss.SSSSyy-MM-dd'S'HH:mm");
select to_timestamp("12.1234019-10-06S10:11", "ss.SSSSy-MM-dd'S'HH:mm");

select to_timestamp("2019-10-06S", "yyyy-MM-dd'S'");
select to_timestamp("S2019-10-06", "'S'yyyy-MM-dd");

select to_timestamp("2019-10-06T10:11:12'12", "yyyy-MM-dd'T'HH:mm:ss''SSSS"); -- middle
select to_timestamp("2019-10-06T10:11:12'", "yyyy-MM-dd'T'HH:mm:ss''"); -- tail
select to_timestamp("'2019-10-06T10:11:12", "''yyyy-MM-dd'T'HH:mm:ss"); -- head
select to_timestamp("P2019-10-06T10:11:12", "'P'yyyy-MM-dd'T'HH:mm:ss"); -- head but as single quote

-- missing fields
select to_timestamp("16", "dd");
select to_timestamp("02-29", "MM-dd");
select to_timestamp("2019 40", "yyyy mm");
select to_timestamp("2019 10:10:10", "yyyy hh:mm:ss");

-- timestamp add/sub operations
select timestamp'2011-11-11 11:11:11' + interval '2' day;
select timestamp'2011-11-11 11:11:11' - interval '2' day;
select timestamp'2011-11-11 11:11:11' + interval '2' second;
select timestamp'2011-11-11 11:11:11' - interval '2' second;
select '2011-11-11 11:11:11' - interval '2' second;
select '1' - interval '2' second;
select 1 - interval '2' second;
-- analyzer will cast date to timestamp automatically
select date'2020-01-01' - timestamp'2019-10-06 10:11:12.345678';
select timestamp'2019-10-06 10:11:12.345678' - date'2020-01-01';
select timestamp'2019-10-06 10:11:12.345678' - null;
select null - timestamp'2019-10-06 10:11:12.345678';

-- Unsupported narrow text style
select to_timestamp('2019-10-06 A', 'yyyy-MM-dd GGGGG');
select to_timestamp('22 05 2020 Friday', 'dd MM yyyy EEEEEE');
select to_timestamp('22 05 2020 Friday', 'dd MM yyyy EEEEE');
select unix_timestamp('22 05 2020 Friday', 'dd MM yyyy EEEEE');
select from_json('{"t":"26/October/2015"}', 't Timestamp', map('timestampFormat', 'dd/MMMMM/yyyy'));
select from_csv('26/October/2015', 't Timestamp', map('timestampFormat', 'dd/MMMMM/yyyy'));
