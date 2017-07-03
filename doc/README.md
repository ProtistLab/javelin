# Javelin - A demo AWS Lambda project

## What

1. A new event gets put on Kenesis queue.  The queue is configured out
   of band in the AWS GUI console.
2. Data in said event is some markdown. (up to about* 1Mb)
3. Lambda converts to .html and writes it to an S3 bucket
   'javelinetest'.


*other parameters eat into the 1Mb budget
