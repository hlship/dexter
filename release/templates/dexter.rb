class Dexter < Formula
  desc "Interactive dependency explorer for Clojure and JVM projects"
  homepage "https://github.com/hlship/dexter"
  url "https://github.com/hlship/dexter/releases/download/{{tag}}/{{zip-name}}"
  version "{{tag}}"
  sha256 "{{sha}}"

  depends_on "java"

  def install
      bin.install "{{uber-jar}}"
      bin.install "aot-training"
      bin.install "dexter"
      chmod 0755, bin/"dexter"
  end

  def post_install
    # Create AOT cache for faster startup (JDK 25+, Project Leyden).
    # Non-fatal: older JDKs skip this gracefully.
    system bin/"dexter", "--aot-train"
  rescue => e
    opoo "AOT cache creation skipped: #{e.message}"
  end

  test do
    system bin/"dexter", "--help"
  end
end
