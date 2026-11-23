INSERT INTO categories(name,slug,description,icon,accent_color) VALUES
('AI','ai','Models, research, and the tools bringing AI into everyday work.','sparkles','#517cf8'),
('Security','security','Security advisories, disclosures, and practical fixes.','shield','#e3856c'),
('New Tech','new-tech','The latest in hardware, platforms, and emerging technology.','layers','#2787ff'),
('Developer','developer','Languages, open source, and the craft of building software.','code','#4874d2'),
('Fintech','fintech','Payments, digital banking, and financial infrastructure.','wallet','#22a7aa');
INSERT INTO sources(name,website_url,feed_url,source_type,enabled) VALUES
('Kubernetes Blog','https://kubernetes.io/blog/','https://kubernetes.io/feed.xml','RSS',true),
('The Go Blog','https://go.dev/blog/','https://go.dev/blog/feed.atom','ATOM',true);
