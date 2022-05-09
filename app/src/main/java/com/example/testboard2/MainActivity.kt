package com.example.testboard2

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.boe.board.BoardView
import com.boe.board.handwriteStatus
import com.boe.board.penType
import com.boe.board.penWidth
import com.boe.board.widget.ArrowShape
import com.boe.board.widget.CircleShape
import com.boe.board.widget.HandwriteWidget
import com.boe.board.widget.RectangleShape
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var boardView: BoardView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        boardView = BoardView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val thumbView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(500, 500).apply {
                gravity = Gravity.END
                topMargin = 100
            }
            visibility = View.GONE
        }

        val lo = LinearLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            orientation = LinearLayout.VERTICAL
        }

        val btnLo = LinearLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        btnLo.addView(Button(this).apply {
            text = "图片"
            setOnClickListener { boardView.addImg("${cacheDir}/test.jpg") }
        })
        btnLo.addView(Button(this).apply {
            text = "文字"
            setOnClickListener { boardView.addText(testText) }
        })
        btnLo.addView(Button(this).apply {
            text = "方形"
            setOnClickListener { boardView.addShape(RectangleShape) }
        })
        btnLo.addView(Button(this).apply {
            text = "圆形"
            setOnClickListener { boardView.addShape(CircleShape) }
        })
        btnLo.addView(Button(this).apply {
            text = "箭头"
            setOnClickListener { boardView.addShape(ArrowShape()) }
        })
        btnLo.addView(Button(this).apply {
            text = "上移一层"
            setOnClickListener { boardView.changeLayer(1) }
        })
        btnLo.addView(Button(this).apply {
            text = "下移一层"
            setOnClickListener { boardView.changeLayer(-1) }
        })
        btnLo.addView(Button(this).apply {
            text = "移到顶层"
            setOnClickListener { boardView.changeLayer(Int.MAX_VALUE) }
        })
        btnLo.addView(Button(this).apply {
            text = "移到底层"
            setOnClickListener { boardView.changeLayer(Int.MIN_VALUE) }
        })
        btnLo.addView(Button(this).apply {
            text = "批量选择"
            setOnClickListener { boardView.batchSelect() }
        })

        lo.addView(btnLo)

        val btnLo2 = LinearLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        btnLo2.addView(Button(this).apply {
            text = "保存"
            setOnClickListener { boardView.save("$cacheDir/save.txt") }
        })
        btnLo2.addView(Button(this).apply {
            text = "加载"
            setOnClickListener { boardView.load("$cacheDir/save.txt") }
        })
        btnLo2.addView(Button(this).apply {
            text = "撤销"
            setOnClickListener { boardView.undo() }
        })
        btnLo2.addView(Button(this).apply {
            text = "重做"
            setOnClickListener { boardView.redo() }
        })
        val handwriteStatusBtn = Button(this).apply {
            text = if (boardView.handwriteStatus) "手写" else "编辑"
            setOnClickListener { boardView.changeHandwriteStatus(!boardView.handwriteStatus) }
        }
        boardView.setOnHandwriteStatusChangeListener {
            handwriteStatusBtn.text = if (boardView.handwriteStatus) "手写" else "编辑"
        }
        btnLo2.addView(handwriteStatusBtn)
        btnLo2.addView(Button(this).apply {
            text = "笔"
            setOnClickListener { boardView.penType = HandwriteWidget.HwPen.PENCIL }
        })
        btnLo2.addView(Button(this).apply {
            text = "橡皮"
            setOnClickListener {
                boardView.penType = HandwriteWidget.HwPen.ERASER
            }
        })
        btnLo2.addView(Button(this).apply {
            text = "画圈擦除"
            setOnClickListener {
                boardView.penType = HandwriteWidget.HwPen.ENCLOSED_ERASER
            }
        })
        btnLo2.addView(Button(this).apply {
            text = "更改比例"
            var scale = 1F
            setOnClickListener {
                scale = if (scale == 1F) 0.5F else 1F
                boardView.setScale(scale)
            }
        })
        btnLo2.addView(Button(this).apply {
            text = "显示缩略图"
            setOnClickListener {
                if (thumbView.visibility == View.VISIBLE) {
                    thumbView.visibility = View.GONE
                    text = "显示缩略图"
                } else {
                    val bm = boardView.generateThumb() ?: return@setOnClickListener
                    thumbView.setImageBitmap(bm)
                    thumbView.visibility = View.VISIBLE
                    text = "隐藏缩略图"
                }
            }
        })

        lo.addView(btnLo2)

        val btnLo3 = LinearLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        btnLo3.addView(SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { weight = 1F }
            max = 50
            min = 1
            progress = boardView.penWidth.toInt()
            this.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar,
                    progress: Int,
                    fromUser: Boolean
                ) {
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {
                }

                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    boardView.penWidth = seekBar.progress.toFloat()
                }
            })
        })

        lo.addView(btnLo3)

        lo.addView(boardView)

        val root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        root.addView(lo)
        root.addView(thumbView)

        setContentView(root)

        resources.openRawResource(R.raw.test).use {
            File("$cacheDir/test.jpg").writeBytes(it.readBytes())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        boardView.dispose()
    }

    private val testText = """
        首页
        下载APP
        会员
        IT技术
        搜索
        Android Uri to Path

        狮_子歌歌
        Android Uri to Path

        狮_子歌歌
        0.725
        2016.10.08 00:03:03
        字数 697
        阅读 13,078
        起因
        Android在4.4之后的版本(包括4.4)中，从相册中选取图片返回Uri进行了改动。所以我们无法通过该Uri来取得文件路径，从而解码图片，将其显示出来。

        在4.3或以下可以直接用Intent.ACTION_GET_CONTENT打开相册;在4.4或以上,官方建议用ACTION_OPEN_DOCUMENT打开相册

        4.4之前的版本
        在4.4之前的版本，返回的Uri如下:content://media/external/images/media/8302

        我们可以通过ContentResolver的查询方法来获取路径:

        Uri uri ＝ "content://media/external/images/media/8302";
        String imagePath = getImagePath(uri, null);
        private String getImagePath(Uri uri, String selection) {
                String path = null;
                Cursor cursor = getContentResolver().query(uri, null, selection, null, null);
                if (cursor != null) {
                    if (cursor.moveToFirst()) {
                        path = cursor.getString(cursor.getColumnIndex(MediaStore.Images.Media.DATA));
                    }

                    cursor.close();
                }
                return path;
            }
        代码分析，当我们通过uri得到了相册数据库图片的表，然后通过索引MediaStore.Images.Media.DATA获取所得行的"_data"列的值。这样我们就得到了具体的文件路径，可以通过创建输入流来获取相应的Bitmap，并进行显示。

        4.4之后的版本，包括4.4
        在4.4之后的，包括4.4的版本，返回的Uri有可能是以下的一种:

        content://com.android.providers.media.documents/document/image%3A8302
        content://com.android.providers.downloads.documents/document/5
        content://media/external/images/media/8302
        我们不能直接通过前两种Uri直接获取到对应的表，所以需要"翻译一下":

        private void handleImageOnKitKat(Intent data) {
                String imagePath = null;
                Uri uri = data.getData();

                if (DocumentsContract.isDocumentUri(this, uri)) {
                    String docId = DocumentsContract.getDocumentId(uri);
                    if ("com.android.providers.media.documents".equals(uri.getAuthority())) {
                        //Log.d(TAG, uri.toString());
                        String id = docId.split(":")[1];
                        String selection = MediaStore.Images.Media._ID + "=" + id;
                        imagePath = getImagePath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, selection);
                    } else if ("com.android.providers.downloads.documents".equals(uri.getAuthority())) {
                        //Log.d(TAG, uri.toString());
                        Uri contentUri = ContentUris.withAppendedId(
                                Uri.parse("content://downloads/public_downloads"),
                                Long.valueOf(docId));
                        imagePath = getImagePath(contentUri, null);
                    }
                } else if ("content".equalsIgnoreCase(uri.getScheme())) {
                    //Log.d(TAG, "content: " + uri.toString());
                    imagePath = getImagePath(uri, null);
                }
            }
        代码分析:

        判断步骤:

        首先对Uri的authority进行判断。是document类型的Uri还是普通的media类型的Uri。
        由于document类型有两种:media和download类型，所以需要进一步判断。因为这里涉及Uri的id部分不同。
        如果是普通类型，那么和4.4之前的处理完全一样。可以直接通过Uri获取文件路径。
        如果是media类型的document Uri，我们首先通过DocumentsContract.getDocumentId(uri);获取到"image%3A8302"。然后通过String.split方法来获取真正的id。

        这里为什么用":"来作为分隔符我也不是很清楚...而且还学到一个东西ContentResolver.query()方法中selection参数可以不用占位符"?"直接获取指定想要的列。

        MediaStore.Images.Media._ID指的是我们获取到指定image文件的id在表中的列名。MediaStore.Images.Media.EXTERNAL_CONTENT_URI则是相册图片表真正的标示符。

        这里的Uri获取是通过打开相册选定图片后，返回的Intent中获取的。

        当我们获取到选中图片真正的Uri后，就可以通过之前的getImagePath()方法来获取表中的文件路径。最终达到解码图片的目的。

        实际效果
        Uri To Path.gif
        参考
        Android 4.4从图库选择图片,获取图片路径并裁剪

        Android-Uri To Path

        《第一行代码》

        更多精彩内容，就在简书APP
        "小礼物走一走，来简书关注我"
        还没有人赞赏，支持一下
          
        狮_子歌歌
        趁年轻，做自己想做的事情！
        总资产6共写了12.6W字获得284个赞共109个粉丝
        写下你的评论...
        全部评论
        15

        一个简单搬运工
        6楼 2018.03.07 09:35
        :的URL编码后是%3A，所以。。。

        21b0eca541e0
        5楼 2017.09.30 14:18
        感谢分享！

        47fe3088500c
        4楼 2017.06.21 15:33
        非常清晰 非常好 感谢分享...

        slimvan
        3楼 2017.02.28 11:27
        厉害了楼主，问题在这里得到解决。

        狮_子歌歌
        2017.02.28 11:47
        @slimvan 谢谢:smile:

        莎莎氧oO
        2楼 2016.12.06 11:43
        找了好久，，终于找到正确的答案，，感谢小编，，另外，还需要一个权限。
        <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"/>

        狮_子歌歌
        2016.12.06 12:12
        @o氧气oO 莫非大兄弟也是在看第一行代码遇到坑了吗

        狮_子歌歌
        2016.12.06 15:55
        @o氧气oO 你可以打印出你返回的url看看

        莎莎氧oO
        2016.12.06 17:30
        @FoolishDev content://com.android.externalstorage.documents/document/primary%3AIMG_20120429_173121.jpg
        被以下专题收入，发现更多相似内容

        Android...
        推荐阅读
        更多精彩内容
        理解Android图像处理-拍照、单/多图选择器及图像优化
        如以上DEMO截图所示效果，我们对于这种类似的功能肯定不算陌生，因为这可以说是实际开发中一类非常常见的功能需求了。...

        Machivellia
        阅读 1,397
        评论 0
        赞 12
        源码众享最新教程 - 草稿
        ¥开启¥ 【iAPP实现进入界面执行逐一显】 〖2017-08-25 15:22:14〗 《//首先开一个线程，因...

        小菜c
        阅读 5,099
        评论 0
        赞 17
        Android 4.4从图库选择图片,获取图片路径并裁剪
        原文章地址：Android 4.4从图库选择图片,获取图片路径并裁剪 最近在做一个从图库选择图片或拍照,然后裁剪的...

        如若成枫
        阅读 1,032
        评论 0
        赞 3
        白露时节养生有哪些注意事项
        今天（9月7日）将迎来第15个节气——白露。进入白露节气，基本可以宣告进入秋季了，全国普遍进入凉爽天气，那么，到了...

        伍1
        阅读 326
        评论 0
        赞 0

        春风十里
        最近，莫名地看起了电视剧，作为一个对长篇电视剧生来恐惧的人，或许是脑神经的间歇性短路。让我转了性子，看起了目前很火...

        波风小王子
        阅读 115
        评论 0
        赞 2
        美妆相机体验报告
        一、体验环境 体验机型：iPhone6 plus 体验系统：iOS 体验版本：3.7.1 体验人：幺幺小精 体验时...

        小影子__
        阅读 645
        评论 3
        赞 1
        2017-11-11
        姐姐说：乐什么乐！ 都没有人给你清空购物车！！ 好吧，别这样， 狗也是有伤心感受的

        米饭未熟
        阅读 92
        评论 0
        赞 1


        狮_子歌歌
        总资产6
        Android中缓存理解（二）
        阅读 844
        Android中缓存理解（一）
        阅读 2,105
        热门故事
        闺蜜的一条朋友圈，结束了我和老公5年的婚姻
        娘家拆迁分了两套新房，婆婆让我把房本写上小叔子的名？
        前任一哭，现任必输
        老婆偷偷拿30万给小舅子买豪车，被我一招“制服”
        推荐阅读
        iOS 配置QQ分享
        阅读 462
        iOS 获取服务器时间问题
        阅读 352
        vue-router 路由动态传参 query和params的区别
        阅读 438
        42.HTTP Header之Content-Type
        阅读 190
        Protobuf在Android中的基本使用
        阅读 1,327
        写下你的评论...

    """.trimIndent()
}