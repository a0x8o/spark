-- test cases for bitwise functions

-- null
select bit_count(null);

-- boolean
select bit_count(true);
select bit_count(false);

-- byte/tinyint
select bit_count(cast(1 as tinyint));
select bit_count(cast(2 as tinyint));
select bit_count(cast(3 as tinyint));

-- short/smallint
select bit_count(1S);
select bit_count(2S);
select bit_count(3S);

-- int
select bit_count(1);
select bit_count(2);
select bit_count(3);

-- long/bigint
select bit_count(1L);
select bit_count(2L);
select bit_count(3L);

-- negative num
select bit_count(-1L);

-- edge value
select bit_count(9223372036854775807L);
select bit_count(-9223372036854775808L);

-- other illegal arguments
select bit_count("bit count");
select bit_count('a');
