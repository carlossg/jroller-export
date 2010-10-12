// Script by Jakub Holy
// from http://theholyjava.wordpress.com/2010/05/22/migrating-from-jroller-to-wordpress/

/**
 a Groovy (a Java-like scripting language) script that converts the posts and comments exported from JRoller into a fragment of the WordPress export format (WXR). This fragment will only contain a list of <item>s representing your posts with comments embedded as <wp:comment>s. You will then need to add a proper header and footer to turn it into a valid import file.

 Among others, the script tries to fix problems with tags within <pre>…</pre>, namely it replaces <br> with a new line because this tag would be simply stripped by WP.

 How to use it:

    1. Download Groovy 1.7.2 or higher, unpack it, run the Groovy console GUI (bin/groovyConsole[.bat]), paste there the script provided below
    2. Modify the configuration, namely  change inputFileUrl to point to your JRoller backup file, outputFilePath to where you want to store the output, and defaultAuthor to your WP user name
           * Note: The base url for is not important as it will be replaced with the target blog’s URL.
    3. Run the script in the Groovy console (Script -> Run); it should log something into the output window and the output file should be created
 */


// CONVERT JROLLER BACKUP TO WORDPRESS WXR FRAGMENT
// CONFIGURATION SECTION ######################
final int basePostId = 100 // I belive this isn't importatn as WP will assign an ID it sees fit...
final String inputFileUrl = "file:jroller_bak_all.xml"
final String outputFilePath = "wordpress_import-items_only-1.xml"
final String defaultAuthor = "admin"
// /CONFIGURATION SECTION ######################

// vars: entry, postId, postBody, postName, category, postDate
// NOTE: WP uses regular expressions to read the input, not a XML parser => it's essential to keep the proper format including spaces etc.
def entryTplTxt = """
<item>
<title>\${entry.title}</title>
<link>http://theholyjava.wordpress.com/\${postDate.format("yyyy/MM/dd")}/\${postName}/</link>
<pubDate>\${postDate.format("EEE, dd MMM yyyy HH:mm:ss")} +0000</pubDate>
<dc:creator><![CDATA[${defaultAuthor}]]></dc:creator>
<category><![CDATA[\${category}]]></category>
<category domain="category" nicename="\${category.toLowerCase()}"><![CDATA[\${category}]]></category>
<guid isPermaLink="false"></guid>
<description></description>
<content:encoded><![CDATA[\${postBody}]]></content:encoded>
<excerpt:encoded><![CDATA[]]></excerpt:encoded>
<wp:post_id>\$postId</wp:post_id>
<wp:post_date>\${postDate.format("yyyy-MM-dd HH:mm:ss")}</wp:post_date>
<wp:post_date_gmt>\${postDate.format("yyyy-MM-dd HH:mm:ss")}</wp:post_date_gmt>
<wp:comment_status>open</wp:comment_status>
<wp:ping_status>open</wp:ping_status>
<wp:post_name>\${postName}</wp:post_name>
<wp:status>publish</wp:status>
<wp:post_parent>0</wp:post_parent>
<wp:menu_order>0</wp:menu_order>
<wp:post_type>post</wp:post_type>
<wp:post_password></wp:post_password>
<wp:is_sticky>0</wp:is_sticky>
""" // close it with '</item>' after adding comments!

// vars: comment, commentId >= 1
def commentTplTxt = """
<wp:comment>
<wp:comment_id>\$commentId</wp:comment_id>
<wp:comment_author><![CDATA[\${comment.author.name}]]></wp:comment_author>
<wp:comment_author_email>\${comment.author.email}</wp:comment_author_email>
<wp:comment_author_url>\${comment.author.url}</wp:comment_author_url>
<wp:comment_author_IP></wp:comment_author_IP>
<wp:comment_date>\${postDate.format("yyyy-MM-dd HH:mm:ss")}</wp:comment_date>
<wp:comment_date_gmt>\${postDate.format("yyyy-MM-dd HH:mm:ss")}</wp:comment_date_gmt>
<wp:comment_content><![CDATA[\${comment.content}]]></wp:comment_content>
<wp:comment_approved>1</wp:comment_approved>
<wp:comment_type></wp:comment_type>
<wp:comment_parent>0</wp:comment_parent>
<wp:comment_user_id>0</wp:comment_user_id>
</wp:comment>
"""

def engine = new groovy.text.SimpleTemplateEngine()
def entryTpl = engine.createTemplate(entryTplTxt)
def commentTpl = engine.createTemplate(commentTplTxt)

def blog = new XmlSlurper(false,false).parse(inputFileUrl)
def output = new File(outputFilePath)
output.createNewFile()
//assert 30 == blog.entry.size() : "actual: ${blog.entry.size()}"

// turn a post title into a string that can be used in the post's URL
private String makePostName(String title, int postId, Set postNameSet) {

        def postName = java.net.URLEncoder.encode(
            title.replaceAll("\\s", "-")
            ,"UTF-8")
            .replaceAll("%..","");
        postName = postName.substring(0,Math.min(34, postName.length())).toLowerCase()

        // Ensure postName is unique:
        while (! postNameSet.add(postName)) {
            postName = postId + postName.substring(0, postName.length()-2)
        }

        return postName
}

// replace <br> and other formatting markup within <pre> segment with \n, ' ' etc.;
// WP would drop <br> thus destroying the formatting
private String fixMarkupWithinPre(final String postContent) {
        return postContent.replaceAll(/(?is)<\s*pre\s*>.*?<\s*\/\s*pre\s*>/,
         { preFrag -> return preFrag
             .replaceAll(/(?ius)<\s*br\s*\/?\s*>/, '\n')
             .replaceAll(/(?ius)&nbsp;/, ' ')
             .replaceAll(/(?ius)&quot;/, '"')
         })
}

def postId = basePostId
def commentId
def postNameSet = [] as Set
def categories = [] as Set

blog.entry.each(){
    it ->
    def postDate = Date.parse("yyyy-MM-dd'T'HH:mm:ss", it.issued.text())
    // a comment?
    if(it.annotate.size() > 0) {
        output.append commentTpl.make([comment:it, commentId:(++commentId), postDate:postDate]).toString()
    } else {
        // Close the previous post:
        if (postId > basePostId) {  output.append "</item>" }
        ++postId
        commentId = 0 // reset for the next post

        def category = it.subject.text().replaceFirst("/","")
        categories << category
        output.append entryTpl.make([
            entry:it, postId:postId, postDate:postDate
            , postName:makePostName(it.title.text(), postId, postNameSet)
            , postBody: fixMarkupWithinPre(it.content.text())
            , category:category])
            .toString()
    }
}
// Close the final post
if (postId > 0) {  output.append "</item>" }

println "The posts used the following categorie, which will be thus created in WP: $categories"
"done; check $output"
