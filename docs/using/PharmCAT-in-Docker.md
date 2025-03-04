---
parent: Using PharmCAT
title: PharmCAT in Docker
permalink: using/PharmCAT-in-Docker/
nav_order: 6
render_with_liquid: false
---
# PharmCAT in Docker

PharmCAT is available in a Docker container.

## Setup

If you are not familiar with Docker, this [overview](https://docs.docker.com/get-started/overview/) is a good starting point.

You must have Docker [installed](https://docs.docker.com/get-docker/) to use PharmCAT via Docker.

Then you can get PharmCAT from [Docker Hub](https://hub.docker.com/r/pgkb/pharmcat):

```console
# docker pull pgkb/pharmcat
```

## Usage

You will need to make your data accessible to the Docker container. There are
[several options](https://docs.docker.com/storage/) to choose from, and you will have to decide what works best for you.
For example, a volume mount is the best for persisting data, but will take some configuration.

This tutorial will use bind mounts because they are the easiest to use and requires no prior configuration.

General usage:

```console
# docker run --rm -v /path/to/data:/pharmcat/data pgkb/pharmcat <xxx>
```

--rm
: Cleans up the container automatically when you're done with it

-v
: Bind mounts `/path/to/data` on your machine to `/pharmcat/data` in the Docker image.  This will make the data available under the `data` subdirectory.

`<xxx>`
: Command to run

If you run `ls`, it will list the contents of the `/pharmcat` directory:

```console
# docker run --rm -v /path/to/data:/pharmcat/data pgkb/pharmcat ls
pharmcat_vcf_preprocessor.py
data
pharmcat
pharmcat.jar
pharmcat_pipeline
pharmcat_positions.uniallelic.vcf.bgz
pharmcat_positions.uniallelic.vcf.bgz.csi
pharmcat_positions.vcf
pharmcat_positions.vcf.bgz
pharmcat_positions.vcf.bgz.csi
preprocessor
reference.fna.bgz
reference.fna.bgz.fai
reference.fna.bgz.gzi
vcf_preprocess_exceptions.py
vcf_preprocess_utilities.py
```


### Running the PharmCAT pipeline

The [Pharmcat pipeline](/using/Running-PharmCAT-Pipeline) combines the [VCF preprocessor](/using/VCF-Preprocessor) and
the core [PharmCAT tool](/using/Running-PharmCAT).  You should be familiar with both tools and their requirements.

That said, this is the easiest way to run PharmCAT:

```console
# docker run --rm -v /path/to/data:/pharmcat/data pgkb/pharmcat ./pharmcat_pipeline <vcf_file>
```

If you have a file `/path/to/data/sample.vcf`, you would use:

```console
# docker run --rm -v /path/to/data:/pharmcat/data pgkb/pharmcat ./pharmcat_pipeline data/sample.vcf
```


### Running the VCF Preprocessor

Your VCF files needs to comply with [PharmCAT's requirements](/using/VCF-Requirements).  [PharmCAT's VCF Preprocessor](/using/VCF-Preprocessor) will handle much of this for you.

```console
# docker run --rm -v /path/to/data:/pharmcat/data pgkb/pharmcat pharmcat_vcf_preprocessor.py -vcf <vcf_file>
```

If you have a file `/path/to/data/sample.vcf`, you would use:

```console
# docker run --rm -v /path/to/data:/pharmcat/data pgkb/pharmcat pharmcat_vcf_preprocessor.py -vcf data/sample.vcf
```


### Running PharmCAT

```console
# docker run --rm -v /path/to/data:/pharmcat/data pgkb/pharmcat pharmcat <vcf_file>
```

After running the file `/path/to/data/sample.vcf` through the preprocessor, if it was a single sample file, you should 
have gotten a file called `sample.preprocessed.vcf`.  You can then run this through PharmCAT with:

```console
# docker run --rm -v /path/to/data:/pharmcat/data pgkb/pharmcat pharmcat -vcf data/sample.preprocessed.vcf
```

> The Docker image includes the `pharmcat` script, which is just a wrapper around the call to Java.  For details on 
> using PharmCAT, please see the [Running PharmCAT](/using/Running-PharmCAT).
