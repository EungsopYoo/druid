SELECT col7 , col8 , LAG(col8) OVER(PARTITION BY col7 ORDER BY col8 desc) LAG_col8 FROM "allTypsUniq.parquet" WHERE col8 > 'FL' and col8 < 'VT'