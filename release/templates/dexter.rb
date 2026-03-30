class Dexter < Formula
  desc "Interactive dependency explorer for Clojure and JVM projects"
  homepage "https://github.com/hlship/dexter"
  url "https://github.com/hlship/dexter/releases/download/{{tag}}/{{zip-name}}"
  version "{{tag}}"
  sha256 "{{sha}}"

  depends_on "java"

  def install
      bin.install "{{uber-jar}}"
      bin.install "dexter"
      chmod 0755, bin/"dexter"
  end

  test do
    system "./dexter", "--help"
  end
end
