# Redis7 底层数据结构解析


&#x9;这一章节我们将深入理解Redis底层数据结构，也就是尝试真正去了解我们指定的set k1 v1这样的指令，是怎么执行的，数据是怎么保存的。

&#x9;开始之前，做两点简单声明：

&#x9;第一：对于大多数程序员，研究Redis底层结构的目的，只有一个：面试！也就是体现你对Redis的理解深度，而并不是要你去写一个Redis。因此，接下来主要分析常用的几种数据类型的底层结构，中间必然会涉及到一些Redis底层的C源码。对于这些源码，只抽取其中部分精华，用做知识点的佐证。如果之间有逻辑断层，或者你想要了解一些其他的数据类型，可以自行看源码补充。

&#x9;第二：Redis的底层数据结构其实是经常变化的，不光Redis6到Redis7这样的大版本，就算同样大版本下的不同小版本，底层结构也是经常有变化的。对于讲到的每种数据结构，会尽量在Redis源码中进行验证。如果没有说明，Redis的版本是目前最新的7.2.5。


# 一、整体理解Redis底层数据结构

## 1、Redis数据在底层是什么样的？

&#x9;在应用层面，我们熟悉Redis有多种不同的数据类型，比如 string, hash, list, set, zset 等。但是这些数据在Redis的底层是什么样子呢？实际上Redis提供了一个指令OBJECT可以用来查看数据的底层类型。

```shell
127.0.0.1:6379> OBJECT HELP
 1) OBJECT <subcommand> [<arg> [value] [opt] ...]. Subcommands are:
 2) ENCODING <key>
 3)     Return the kind of internal representation used in order to store the value
 4)     associated with a <key>.
 5) FREQ <key>
 6)     Return the access frequency index of the <key>. The returned integer is
 7)     proportional to the logarithm of the recent access frequency of the key.
 8) IDLETIME <key>
 9)     Return the idle time of the <key>, that is the approximated number of
10)     seconds elapsed since the last access to the key.
11) REFCOUNT <key>
12)     Return the number of references of the value associated with the specified
13)     <key>.
14) HELP
15)     Print this help.

127.0.0.1:6379> set k1 v1
OK
127.0.0.1:6379> OBJECT ENCODING k1
"embstr"
```

&#x9;可以看到，k1 v1 这个 `<k, v>` 键值对，他在底层的数据类型就是 `embstr `。Redis在底层，其实是这样描述这些数据类型的。

server.h 的 880 行：

```c
/* Objects encoding. Some kind of objects like Strings and Hashes can be
 * internally represented in multiple ways. The 'encoding' field of the object
 * is set to one of this fields for this object. */
#define OBJ_ENCODING_RAW 0     /* Raw representation */
#define OBJ_ENCODING_INT 1     /* Encoded as integer */
#define OBJ_ENCODING_HT 2      /* Encoded as hash table */
#define OBJ_ENCODING_ZIPMAP 3  /* No longer used: old hash encoding. */
#define OBJ_ENCODING_LINKEDLIST 4 /* No longer used: old list encoding. */
#define OBJ_ENCODING_ZIPLIST 5 /* No longer used: old list/hash/zset encoding. */
#define OBJ_ENCODING_INTSET 6  /* Encoded as intset */
#define OBJ_ENCODING_SKIPLIST 7  /* Encoded as skiplist */
#define OBJ_ENCODING_EMBSTR 8  /* Embedded sds string encoding */
#define OBJ_ENCODING_QUICKLIST 9 /* Encoded as linked list of listpacks */
#define OBJ_ENCODING_STREAM 10 /* Encoded as a radix tree of listpacks */
#define OBJ_ENCODING_LISTPACK 11 /* Encoded as a listpack */
```

> 这里也能看到有些类型已经不再使用了。比如ZIPLIST。如果你看过一些以前的Redis的文章，就会知道，ZIPLIST是在Redis6中经常使用的一个重要的数据类型。但是现在已经不再使用了。在Redis7中，基本已经使用listpack替代了ziplist。

&#x9;然后，在上面的注释中还可以看到。这些编码方式都是使用在Object的encoding字段里的。这个Object是什么呢？

server.h 的 900行：

```c
struct redisObject {
    unsigned type:4;
    unsigned encoding:4;
    unsigned lru:LRU_BITS; /* LRU time (relative to global lru_clock) or
                            * LFU data (least significant 8 bits frequency
                            * and most significant 16 bits access time). */
    int refcount;
    void *ptr;
};
```

&#x9;Redis是一个 `<k, v>` 型的数据库，其中key通常都是string类型的字符串对象，而value在底层就统一是 redisObject 对象。

&#x9;而这个redisObject结构，实际上就是Redis内部抽象出来的一个封装所有底层数据结构的统一对象。这就类似于Java的面向对象的设计方式。

&#x9;这里面几个核心字段意义如下：

*   type：Redis的上层数据类型。比如string,hash,set等，可以使用指令type key查看。
*   encoding： Redis内部的数据类型。
*   lru：当内存超限时会采用LRU算法清除内存中的对象。关于LRU与LFU，在redis.conf中有描述

```conf
# LRU means Least Recently Used
# LFU means Least Frequently Used
```

*   refcount：表示对象的引用次数。可以使用OBJECT REFCOUNT key 指令查看。
*   \*ptr：这是一个指针，指向真正底层的数据结构。encoding只是一个类型描述。实际数据是保存在ptr指向的具体结构里。