<?php
error_reporting(E_ALL);
ini_set("display_errors", 1);
$video_ID = "7lCDEYXw3mM";
$jsonURL = file_get_contents("https://www.googleapis.com/youtube/v3/videos?id={$video_ID}&key=AIzaSyBJVHhh3kSRdHTpFz3zZ-59bCsOgUBwXhw&part=statistics");
$json = json_decode($jsonURL);
$views = $json->{'items'}[0]->{'statistics'}->{'viewCount'};
echo number_format($views,0,'.',',');
?>

